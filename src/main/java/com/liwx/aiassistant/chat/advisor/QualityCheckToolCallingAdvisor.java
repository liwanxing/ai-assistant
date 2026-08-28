package com.liwx.aiassistant.chat.advisor;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityChecker;

/**
 * 继承 ToolCallingAdvisor 的体验版：重写工具调用循环的钩子，实现"质检判断 → 重发一次"
 *
 * 父类 adviseCall 的 do-while 每轮节奏（对照 2.0.0 源码）：
 *   doBeforeCall（调 LLM 前）→ chain.copy(this).nextCall() 调下游链 → doAfterCall（LLM 响应后、工具执行前）
 *   → 响应带 ToolCall 则执行工具、结果拼进对话历史，进下一轮；纯文本则退出循环 → doFinalizeLoop
 *
 * 所以 doAfterCall 每轮都触发，且能区分两种响应：
 *   中间轮：带 ToolCall（text 为 null）——模型的工具决策，意图识别结果在这看
 *   最后一轮：纯文本——最终回答，质检判断放这里
 *
 * 重发机制：doAfterCall 的返回值会被 do-while 拿去判断 isToolCall——
 * 重发时从历史里提取上次工具调用，指令要求"相同参数重调工具"：模型响应带 ToolCall → do-while 判定 true
 * → 框架自动重新执行工具、结果拼进历史 → 下一轮生成新回答。工具的重新执行由循环接管，不用手工调 ToolCallingManager。
 * 若重发后模型直接给了纯文本，循环退出，重发结果成为最终回答。不需要自己写循环控制，这正是继承钩子的好处。
 */
@Slf4j
public class QualityCheckToolCallingAdvisor extends ToolCallingAdvisor {

	/** 质检阈值：最终回答少于这个字数视为不合格，触发重发。体验用，随手改 */
	private static final int MIN_ANSWER_LENGTH = 230;
	/** 最大质检重发次数，防止无限重试 */
	private static final int MAX_RETRIES = 2;

	/**
	 * 当轮请求：doBeforeCall 存、doAfterCall 重发时取。
	 * Advisor 是单例 Bean，多请求并发共用，不能用普通成员变量，ThreadLocal 按线程隔离
	 */
	private final ThreadLocal<ChatClientRequest> currentRequest = new ThreadLocal<>();

	/** 质检重发计数器：记录当前请求已重发次数，达到 MAX_RETRIES 则不再重发 */
	private final ThreadLocal<Integer> retryCount = new ThreadLocal<>();

	/** 轮次计数：只为了日志好读 */
	private final ThreadLocal<Integer> round = new ThreadLocal<>();

	/** 体验版便利构造器：其余参数与默认 ToolCallingAdvisor 完全一致 */
	public QualityCheckToolCallingAdvisor(ToolCallingManager toolCallingManager) {
		super(toolCallingManager, DEFAULT_TOOL_EXECUTION_ELIGIBILITY_CHECKER, DEFAULT_ORDER, true);
	}

	public QualityCheckToolCallingAdvisor(ToolCallingManager toolCallingManager,
			ToolExecutionEligibilityChecker checker, int advisorOrder, boolean conversationHistoryEnabled) {
		super(toolCallingManager, checker, advisorOrder, conversationHistoryEnabled);
	}

	@Override
	protected ChatClientRequest doInitializeLoop(ChatClientRequest chatClientRequest,
			CallAdvisorChain callAdvisorChain) {
		round.set(0);
		retryCount.set(0);
		log.info("[质检Advisor] 工具调用循环开始（每请求一次）");
		return chatClientRequest;
	}

	@Override
	protected ChatClientRequest doBeforeCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
		// 断点A：每轮调 LLM 前停——工具循环多轮时，消息列表一轮比一轮长（多了工具执行结果）
		round.set(currentRound() + 1);
		currentRequest.set(chatClientRequest);
		log.info("[质检Advisor] 第 {} 轮调 LLM，携带消息数：{}", currentRound(),
				chatClientRequest.prompt().getInstructions().size());
		return chatClientRequest;
	}

	@Override
	protected ChatClientResponse doAfterCall(ChatClientResponse chatClientResponse, CallAdvisorChain callAdvisorChain) {
		// 断点B：模型的工具决策就在这——中间轮的响应里能看到模型选了哪个工具、传了什么参数
		ChatResponse chatResponse = chatClientResponse.chatResponse();
		if (chatResponse == null || chatResponse.getResult() == null) {
			return chatClientResponse;
		}
		AssistantMessage output = chatResponse.getResult().getOutput();

		if (chatResponse.hasToolCalls()) {
			log.info("[质检Advisor] 第 {} 轮模型决策调用工具：{}", currentRound(),
					output.getToolCalls().stream().map(c -> c.name() + "(" + c.arguments() + ")").toList());
			return chatClientResponse;
		}

		// ---- 纯文本 = 最终回答，做质检判断 ----
		String text = output.getText();
		log.info("[质检Advisor] 第 {} 轮为最终回答（{} 字）：{}", currentRound(),
				text == null ? 0 : text.length(), text);

		int retries = retryCount.get() == null ? 0 : retryCount.get();
		if (text != null && text.length() < MIN_ANSWER_LENGTH && retries < MAX_RETRIES) {
			retryCount.set(retries + 1);
			log.warn("[质检Advisor] 回答太短（不足 {} 字），第 {} 次质检重发（上限 {}）", MIN_ANSWER_LENGTH, retries + 1, MAX_RETRIES);
			return resendWithFeedback(chatClientResponse, callAdvisorChain, text);
		}
		return chatClientResponse;
	}

	@Override
	protected ChatClientResponse doFinalizeLoop(ChatClientResponse chatClientResponse,
			CallAdvisorChain callAdvisorChain) {
		log.info("[质检Advisor] 工具调用循环结束");
		cleanup();
		return chatClientResponse;
	}

	/**
	 * 重发：先删掉历史里最近一次工具调用交换，再追加"重新调工具"指令，然后 chain.copy(this).nextCall() 调一次 LLM。
	 *
	 * 为什么必须删旧记录：历史里留着旧工具结果时，模型倾向于直接拿旧数据重新作答（纯文本响应），
	 * do-while 判定 isToolCall=false 直接退出，永远进不了下一轮。
	 * 删掉后模型手里没数据，只能重新发起工具调用——这是让 do-while 真正多走一轮的关键。
	 *
	 * copy(this) 与父类循环内的调用方式完全一致：排除自己后直达下游的 ChatModelCallAdvisor，
	 * 不会重穿记忆/缓存等外层 Advisor，也不会递归回本类。
	 *
	 * 关键衔接：重发响应带 ToolCall → doAfterCall 原样返回 → 父类 do-while 判定 isToolCall=true
	 * → ToolCallingManager 自动重新执行工具 → 结果拼进历史 → 下一轮生成新回答
	 */
	private ChatClientResponse resendWithFeedback(ChatClientResponse original, CallAdvisorChain chain,
			String shortText) {
		ChatClientRequest lastRequest = currentRequest.get();
		if (lastRequest == null) {
			return original;
		}

		List<Message> retryInstructions = new ArrayList<>(lastRequest.prompt().getInstructions());
		String lastToolCall = removeLastToolCallExchange(retryInstructions);
		String feedback;
		if (lastToolCall != null) {
			// 调过工具：删了旧记录，指令让模型重新调工具拿新数据再作答
			feedback = "你刚才的回答是\"" + shortText + "\"，质检不满意。"
					+ "请重新调用 " + lastToolCall
					+ " 获取最新实时数据，然后基于工具结果重新回答。";
		}
		else {
			// 没调过工具（纯闲聊回答）：无记录可删，退化为要求重答
			feedback = "你刚才的回答是\"" + shortText + "\"，太简短了。请重新给出更完整、更详细的回答。";
		}
		retryInstructions.add(new UserMessage(feedback));

		ChatClientRequest retryRequest = ChatClientRequest.builder()
			.prompt(new Prompt(retryInstructions, lastRequest.prompt().getOptions()))
			.context(lastRequest.context())
			.build();

		log.warn("[质检Advisor] 重发指令：{}", feedback);
		ChatClientResponse retryResponse = chain.copy(this).nextCall(retryRequest);

		if (retryResponse.chatResponse() != null && retryResponse.chatResponse().hasToolCalls()) {
			// 带 ToolCall：原样返回，do-while 判定 true 后会进下一轮，工具重新执行（断点在 doBeforeCall 能看到消息数增加）
			log.info("[质检Advisor] 重发后模型再次请求调用工具，交给 do-while 循环接管 → 进入下一轮");
		}
		else {
			String retryText = retryResponse.chatResponse() != null && retryResponse.chatResponse().getResult() != null
					? retryResponse.chatResponse().getResult().getOutput().getText()
					: null;
			log.info("[质检Advisor] 重发完成，新回答（{} 字）：{}", retryText == null ? 0 : retryText.length(), retryText);
		}
		return retryResponse;
	}

	/**
	 * 从历史里删除最近一次工具调用交换：带 toolCalls 的 AssistantMessage（模型的"我要调这个工具"消息）
	 * + 其后紧接的 ToolResponseMessage（工具结果消息，一次请求多个工具时有多条）。
	 * 返回被删除调用的 "工具名(参数JSON)" 描述，没调过工具返回 null（不删除任何消息）。
	 *
	 * 工具调用记录长这样（成对出现，不能只删一半，孤儿消息会让 API 报错）：
	 *   [... assistant(toolCalls=[getWeather]) , toolResponse(天气数据) , assistant(最终回答)...]
	 */
	private String removeLastToolCallExchange(List<Message> instructions) {
		for (int i = instructions.size() - 1; i >= 0; i--) {
			Message msg = instructions.get(i);
			if (msg instanceof AssistantMessage am && am.getToolCalls() != null && !am.getToolCalls().isEmpty()) {
				String desc = am.getToolCalls().stream()
					.map(c -> c.name() + "(" + c.arguments() + ")")
					.collect(Collectors.joining(", "));
				// 先记下描述，再删 ToolCall 请求消息 + 后面紧接的全部工具结果消息
				instructions.remove(i);
				while (i < instructions.size() && instructions.get(i) instanceof ToolResponseMessage) {
					instructions.remove(i);
				}
				return desc;
			}
		}
		return null;
	}

	private int currentRound() {
		Integer r = round.get();
		return r == null ? -1 : r;
	}

	private void cleanup() {
		currentRequest.remove();
		retryCount.remove();
		round.remove();
	}

	/**
	 * 子类 Builder：泛型自定型（父类 Builder<T extends Builder<T>> 的标准套路），链式方法返回子类类型。
	 * 注册口子要求 Bean 类型是 ToolCallingAdvisor.Builder<?>，见 AiConfig 里的用法
	 */
	public static class Builder extends ToolCallingAdvisor.Builder<Builder> {

		@Override
		public ToolCallingAdvisor build() {
			return new QualityCheckToolCallingAdvisor(getToolCallingManager(), getToolExecutionEligibilityChecker(),
					getAdvisorOrder(), isConversationHistoryEnabled());
		}

		@Override
		protected Builder newCopy() {
			return new Builder();
		}
	}
}
