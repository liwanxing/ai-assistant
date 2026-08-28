package com.liwx.aiassistant.chat.advisor;

import java.util.ArrayList;
import java.util.List;

import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityChecker;

import reactor.core.publisher.Flux;

/**
 * 质检装饰器（经典包装版）：GoF 装饰器模式——持有并包装一个标准的 ToolCallingAdvisor，
 * 自己占据它原来的链位置（order 不变），所有行为转发 delegate，只在"前后"加增强。
 *
 * 为什么形式上是 extends：注册口子（ChatClientAutoConfiguration 的 @ConditionalOnMissingBean）
 * 要求 Builder.build() 返回 ToolCallingAdvisor 类型，纯接口实现进不了链。
 * 实质是包装：本类所有行为方法全部转发 delegate，父类状态只在构造时初始化、从不使用。
 *
 * 视野（与插层版 QualityCheckDecoratorAdvisor 对照，这是两种玩法的本质差别）：
 * delegate.adviseCall() 一行代码内部跑完整个 do-while（所有轮次、所有工具调用、所有结果拼装），
 * 包装者的代码只在这行调用的"前后"——外层视角：
 * - 前置增强：整个循环开始前（可改入参，但看不到中间轮）
 * - 后置增强：循环已结束，手里是【最终回答】——不是工具结果！
 * 所以本版质检对象是最终回答：太短/含失败关键词 → 反馈追加到完整历史，再调一次
 * delegate.adviseCall() 重跑一遍完整循环（模型可重新调工具）。
 *
 * 两种姿势选型：
 * - 每轮工具结果质检 → 插层（order 在 ToolCallingAdvisor 内层，QualityCheckDecoratorAdvisor）
 * - 循环前后增强/验最终答案 → 本版（包装）
 */
@Slf4j
public class QualityCheckWrappingAdvisor extends ToolCallingAdvisor {

	/** 最终回答质检标准：少于这个字数或含失败关键词视为不合格。体验用，随手改 */
	private static final int MIN_ANSWER_LENGTH = 50;

	private static final List<String> BAD_ANSWER_KEYWORDS = List.of("失败", "不可用", "稍后再试");

	/** 被包装的标准实例：真正的 do-while 循环在这里跑 */
	private final ToolCallingAdvisor delegate;

	/** 整体重跑标记：最多一次，防止"不合格→重跑→还不合格→再重跑"死循环 */
	private final ThreadLocal<Boolean> retried = new ThreadLocal<>();

	/**
	 * delegate 与本类用完全相同的参数构造（参数一致才能占据同一个链位置、行为一致）。
	 * 注意：子类里不能 new 父类的 protected 构造器（Java 规则只允许 super() 调用），改用公开的 builder 构造
	 */
	public QualityCheckWrappingAdvisor(ToolCallingManager toolCallingManager,
			ToolExecutionEligibilityChecker checker, int advisorOrder, boolean conversationHistoryEnabled) {
		super(toolCallingManager, checker, advisorOrder, conversationHistoryEnabled);  // 父类状态闲置，形式上必须初始化
		this.delegate = ToolCallingAdvisor.builder()
				.toolCallingManager(toolCallingManager)
				.toolExecutionEligibilityChecker(checker)
				.advisorOrder(advisorOrder)
				.conversationHistoryEnabled(conversationHistoryEnabled)
				.build();  // 被包装的标准实例
	}

	@Override
	public String getName() {
		return "QualityCheckWrappingAdvisor(包装 " + delegate.getName() + ")";
	}

	@Override
	public int getOrder() {
		return delegate.getOrder();  // 占据被包装者原来的链位置，链上排序不变
	}

	@Override
	public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
		try {
			log.info("[包装质检] 循环开始前（整个工具循环对本类是一次黑盒调用）");
			// ★ 核心一行：整个 do-while（多轮 LLM 调用 + 工具执行）在这行内部跑完才返回 ★
			ChatClientResponse response = delegate.adviseCall(chatClientRequest, callAdvisorChain);

			// ---- 后置增强：循环已结束，手里是最终回答（外层视角看不到中间轮的工具结果） ----
			String answer = extractAnswer(response);
			log.info("[包装质检] 循环结束，最终回答（{} 字）：{}", answer == null ? 0 : answer.length(), answer);

			if (answer != null && isBadAnswer(answer) && !Boolean.TRUE.equals(retried.get())) {
				retried.set(Boolean.TRUE);
				log.warn("[包装质检] 最终回答不合格（太短或含失败字样），带反馈重跑一遍完整循环");
				// 完整历史保留（含工具结果）——外层重跑不删记录，与插层版"删记录逼重调"是两种思路
				List<Message> retryInstructions = new ArrayList<>(chatClientRequest.prompt().getInstructions());
				retryInstructions.add(new UserMessage("你刚才的回答是\"" + answer + "\"，质检不合格（太简短或包含失败提示）。"
						+ "请基于已有信息重新给出完整、可用的回答。"));
				ChatClientRequest retryRequest = ChatClientRequest.builder()
					.prompt(new Prompt(retryInstructions, chatClientRequest.prompt().getOptions()))
					.context(chatClientRequest.context())
					.build();
				// 关键：重跑也调 delegate（不是 chain.nextCall）——这样才重新经过 do-while，
				// 模型若再次请求工具，循环照常执行
				response = delegate.adviseCall(retryRequest, callAdvisorChain);
				String retryAnswer = extractAnswer(response);
				log.info("[包装质检] 重跑完成，新回答（{} 字）：{}", retryAnswer == null ? 0 : retryAnswer.length(),
						retryAnswer);
			}
			return response;
		}
		finally {
			// 外层每请求只进一次本方法，finally 清理最稳（异常路径也不残留）
			retried.remove();
		}
	}

	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
			StreamAdvisorChain streamAdvisorChain) {
		// 流式：纯转发不做质检——流式的"最终回答"要聚合完才能判断，体验版从简
		return delegate.adviseStream(chatClientRequest, streamAdvisorChain);
	}

	private String extractAnswer(ChatClientResponse response) {
		if (response == null || response.chatResponse() == null || response.chatResponse().getResult() == null) {
			return null;
		}
		return response.chatResponse().getResult().getOutput().getText();
	}

	private boolean isBadAnswer(String answer) {
		return answer.length() < MIN_ANSWER_LENGTH
				|| BAD_ANSWER_KEYWORDS.stream().anyMatch(answer::contains);
	}

	/**
	 * 子类 Builder：build() 返回"包装了标准实例"的装饰器（标准实例在构造器内部 new 出来）。
	 * 注册口子要求 Bean 类型是 ToolCallingAdvisor.Builder<?>，见 AiConfig 里的用法
	 */
	public static class Builder extends ToolCallingAdvisor.Builder<Builder> {

		@Override
		public ToolCallingAdvisor build() {
			return new QualityCheckWrappingAdvisor(getToolCallingManager(), getToolExecutionEligibilityChecker(),
					getAdvisorOrder(), isConversationHistoryEnabled());
		}

		@Override
		protected Builder newCopy() {
			return new Builder();
		}
	}
}
