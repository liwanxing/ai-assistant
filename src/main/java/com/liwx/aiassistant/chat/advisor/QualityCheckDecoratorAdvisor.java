package com.liwx.aiassistant.chat.advisor;

import java.util.ArrayList;
import java.util.List;

import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 质检装饰器（工具结果版）：实现 CallAdvisor 接口，不继承 ToolCallingAdvisor
 *
 * 与继承版的区别：继承版只能用父类给的钩子（doAfterCall 在工具执行"前"触发，拿不到结果），
 * 装饰器靠 order 控制自己在链上的位置——order 设为 ToolCallingAdvisor.DEFAULT_ORDER + 100，
 * 被排进工具循环"内层"：每一轮 do-while 的去程都从这过，第 2 轮起 instructions 末尾
 * 就是上一轮刚执行完的工具结果——工具结果质检放这，时机刚好。
 *
 * 需求：一轮并行调用 A、B、C 三个工具后，质检三个工具的结果；不满意 → 删掉这段
 * 工具调用记录 + 追加"重新调用"指令 → 模型手里没结果只能重新调 → do-while 自然继续。
 *
 * 与继承版打回机制同款（删记录逼模型重调），但不需要替换 ToolCallingAdvisor——
 * 直接挂 defaultAdvisors 即可，链上唯一的 ToolAdvisor 还是框架默认的那个。
 */
@Slf4j
public class QualityCheckDecoratorAdvisor implements CallAdvisor {

	/**
	 * 工具结果的失败关键词：responseData 命中即视为"结果不满意"。
	 * 体验用——正好覆盖项目里 WeatherTool/CircuitBreakerToolCallback 的失败文案
	 * （"天气查询失败"、"服务暂时不可用（熔断保护中）"等）；生产上应换成业务校验（如解析 JSON 检查字段）
	 */
	private static final List<String> BAD_RESULT_KEYWORDS = List.of("失败", "不可用", "熔断", "稍后再试");

	/**
	 * 每次请求最多打回一次，防止"失败→打回→重调→还失败→再打回"死循环。
	 * Advisor 是单例 Bean，多请求并发共用，ThreadLocal 按线程隔离
	 */
	private final ThreadLocal<Boolean> rejected = new ThreadLocal<>();

	@Override
	public String getName() {
		return "QualityCheckDecoratorAdvisor";
	}

	/**
	 * 关键：order 必须"大于" ToolCallingAdvisor.DEFAULT_ORDER（HIGHEST_PRECEDENCE+300）才是循环内层。
	 * order 数值越小越靠洋葱外层：小于 DEFAULT_ORDER = 循环外（整个请求只过一次，只看得到最终回答）；
	 * 大于 DEFAULT_ORDER = 循环内（每轮工具循环都过，看得到每轮的工具结果）
	 */
	@Override
	public int getOrder() {
		return ToolCallingAdvisor.DEFAULT_ORDER + 100;
	}

	@Override
	public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
		// 去程：质检上一轮的工具结果（第 1 轮没有工具结果，原样放行）
		ChatClientRequest processed = inspectToolResults(request);
		ChatClientResponse response = chain.nextCall(processed);

		// 回程：响应不带 ToolCall 说明是最终回答、整个工具循环即将结束——清理打回标记
		// （循环内每轮都过这，只有最后一轮回程的响应才是纯文本）
		if (response.chatResponse() == null || !response.chatResponse().hasToolCalls()) {
			rejected.remove();
		}
		return response;
	}

	/**
	 * 工具结果质检（去程改写请求）：
	 * 上一轮的工具交换在历史末尾长这样（A、B、C 一次请求时 toolCalls 里有三个）：
	 *   [..., user 问题, assistant(toolCalls=[A,B,C]), 工具结果消息(可能一条带三个结果，也可能多条)]
	 * 不满意的处理：删掉这整段记录、追加"重新调用"指令——模型没有结果可用，只能重新发起调用。
	 * 取整段删而不是只删失败的：assistant 的 toolCalls 与工具结果必须成对，删一半会因
	 * tool_call_id 对不上被 API 拒绝；代价是合格的工具也会跟着重调一遍（体验取舍）
	 */
	private ChatClientRequest inspectToolResults(ChatClientRequest request) {
		List<Message> instructions = request.prompt().getInstructions();

		// 反向扫描：从末尾收集连续的工具结果消息，再往前找对应的 assistant(toolCalls) 消息
		int exchangeStart = -1;
		List<ToolResponseMessage.ToolResponse> toolResults = new ArrayList<>();
		for (int i = instructions.size() - 1; i >= 0; i--) {
			Message msg = instructions.get(i);
			if (msg instanceof ToolResponseMessage trm) {
				toolResults.addAll(0, trm.getResponses());  // addAll(0,...) 反向收集后保持原始顺序
			}
			else if (msg instanceof AssistantMessage am && am.getToolCalls() != null && !am.getToolCalls().isEmpty()) {
				exchangeStart = i;  // 找到工具调用请求消息，扫描结束
				break;
			}
			else {
				break;  // 中间夹了其他消息（如最终回答）说明不是紧邻末尾的工具交换，不处理
			}
		}

		if (exchangeStart < 0 || toolResults.isEmpty() || Boolean.TRUE.equals(rejected.get())) {
			return request;  // 第 1 轮（无工具结果）或已打回过：放行
		}

		// 质检：A、B、C 任何一个结果命中失败关键词 → 整段打回
		List<String> badTools = toolResults.stream()
			.filter(r -> isBadResult(r.responseData()))
			.map(ToolResponseMessage.ToolResponse::name)
			.toList();
		if (badTools.isEmpty()) {
			log.info("[装饰器质检] 工具结果合格：{}", toolResults.stream().map(r -> r.name()).toList());
			return request;
		}

		rejected.set(Boolean.TRUE);
		List<Message> cleaned = new ArrayList<>(instructions.subList(0, exchangeStart));  // 删掉整段工具交换
		String feedback = "刚才调用的工具 " + String.join("、", badTools)
				+ " 返回了异常结果（失败/不可用）。请重新调用这些工具获取数据，然后再回答。";
		cleaned.add(new UserMessage(feedback));
		log.warn("[装饰器质检] 工具结果不合格：{}，删除旧记录并要求重新调用", badTools);

		return ChatClientRequest.builder()
			.prompt(new Prompt(cleaned, request.prompt().getOptions()))
			.context(request.context())
			.build();
	}

	/** 质检标准：结果为空/过短/命中失败关键词都算不满意 */
	private boolean isBadResult(String responseData) {
		if (responseData == null || responseData.isBlank()) {
			return true;
		}
		return BAD_RESULT_KEYWORDS.stream().anyMatch(responseData::contains);
	}
}
