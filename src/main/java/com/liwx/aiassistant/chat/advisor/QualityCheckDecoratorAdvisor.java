package com.liwx.aiassistant.chat.advisor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
	 * 每次请求最多打回次数：不合格 → 打回重调 → 还不合格 → 还能再打回，达到上限后放行止损。防止死循环
	 */
	private static final int MAX_REJECTS = 2;

	/**
	 * 当前请求已打回次数。Advisor 是单例 Bean，多请求并发共用，ThreadLocal 按线程隔离
	 */
	private final ThreadLocal<Integer> rejectCount = new ThreadLocal<>();

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

		// 回程：响应不带 ToolCall 说明是最终回答、整个工具循环即将结束——清理打回计数
		// （循环内每轮都过这，只有最后一轮回程的响应才是纯文本）
		if (response.chatResponse() == null || !response.chatResponse().hasToolCalls()) {
			rejectCount.remove();
		}
		return response;
	}

	/**
	 * 工具结果质检（去程改写请求）——全量版：
	 * 扫描整个历史里所有工具交换段（串行调用 A、B、C 各占一段：[assistant(toolCalls), 工具结果]；
	 * 一轮并行调多个工具共用一段），对本次请求真正调过的每个工具结果逐一质检，
	 * 全部合格才算过——工具数量动态（这次 3 个下次 4 个）也不影响，查的是"历史上实际发生了的"。
	 *
	 * 为什么每轮都全量检查而不是"等全部调完再查"：不需要等——每轮去程都在模型正前方，
	 * 第 3 轮就能查出第 2 轮 B 的坏结果当场打回，后面的 C、D 不用白跑；
	 * "所有工具调完"的那一刻也必经本切面（模型拿全部结果生成最终回答的那一轮去程），天然兑底
	 *
	 * 不合格的处理：删除所有含不合格工具的交换段、追加点名重调指令——模型没有结果可用，
	 * 只能重新发起调用。串行场景按段删除互不影响（每段自带配对）；并行段内若有合格工具会陪跑重调
	 * （assistant 的 toolCalls 与结果必须成对，不能只删一半）
	 */
	private ChatClientRequest inspectToolResults(ChatClientRequest request) {
		List<Message> instructions = request.prompt().getInstructions();

		List<ToolSegment> segments = findToolSegments(instructions);
		if (segments.isEmpty()) {
			return request;  // 第 1 轮（还没有任何工具结果）
		}

		// 全量质检：所有段里的所有工具结果（如 4 个数据分析工具，每个都合格才算过）
		List<String> badTools = segments.stream()
			.flatMap(seg -> seg.responses().stream())
			.filter(r -> isBadResult(r.responseData()))
			.map(ToolResponseMessage.ToolResponse::name)
			.toList();
		if (badTools.isEmpty()) {
			log.info("[装饰器质检] 全部工具结果合格：{}",
					segments.stream().flatMap(seg -> seg.responses().stream()).map(r -> r.name()).toList());
			return request;
		}

		int rejects = rejectCount.get() == null ? 0 : rejectCount.get();
		if (rejects >= MAX_REJECTS) {
			log.warn("[装饰器质检] 工具结果仍不合格：{}，已达打回上限 {} 次，放行止损", badTools, MAX_REJECTS);
			return request;
		}
		rejectCount.set(rejects + 1);

		// 删除所有含不合格工具的段（并行段内合格工具陪删），重建历史并追加重调指令
		Set<Integer> removeIndexes = new HashSet<>();
		for (ToolSegment seg : segments) {
			if (seg.responses().stream().anyMatch(r -> isBadResult(r.responseData()))) {
				for (int i = seg.start(); i < seg.end(); i++) {
					removeIndexes.add(i);
				}
			}
		}
		List<Message> cleaned = new ArrayList<>();
		for (int i = 0; i < instructions.size(); i++) {
			if (!removeIndexes.contains(i)) {
				cleaned.add(instructions.get(i));
			}
		}
		String feedback = "刚才调用的工具 " + String.join("、", badTools)
				+ " 返回了不合格结果。请重新调用这些工具获取数据，然后再回答。";
		cleaned.add(new UserMessage(feedback));
		log.warn("[装饰器质检] 工具结果不合格：{}，第 {}/{} 次打回，删除相关记录并要求重新调用",
				badTools, rejects + 1, MAX_REJECTS);

		return ChatClientRequest.builder()
			.prompt(new Prompt(cleaned, request.prompt().getOptions()))
			.context(request.context())
			.build();
	}

	/**
	 * 扫描整个历史，切出所有工具交换段：
	 * [start, end)：assistant(toolCalls) 起始，其后紧邻的全部工具结果消息（不含 end）。
	 * 串行调用 A→B→C 时是三段；一轮并行 [A,B,C] 是一段
	 */
	private List<ToolSegment> findToolSegments(List<Message> instructions) {
		List<ToolSegment> segments = new ArrayList<>();
		for (int i = 0; i < instructions.size(); i++) {
			Message msg = instructions.get(i);
			if (msg instanceof AssistantMessage am && am.getToolCalls() != null && !am.getToolCalls().isEmpty()) {
				int start = i;
				List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
				int j = i + 1;
				while (j < instructions.size() && instructions.get(j) instanceof ToolResponseMessage trm) {
					responses.addAll(trm.getResponses());
					j++;
				}
				segments.add(new ToolSegment(start, j, responses));
				i = j - 1;  // 跳过本段（for 的 i++ 会再走一步）
			}
		}
		return segments;
	}

	/** 历史里一段完整的工具交换：[start, end)，以及段内全部工具结果 */
	private record ToolSegment(int start, int end, List<ToolResponseMessage.ToolResponse> responses) {
	}

	/**
	 * 质检标准：结果为空/命中失败关键词即不合格。
	 * 想接"80 分/通过标志"类标准：让工具返回 JSON（如 {"passed":true,"score":85}），
	 * 这里改成解析 JSON 判断字段——判断逻辑与拦截机制完全解耦，随便换
	 */
	private boolean isBadResult(String responseData) {
		if (responseData == null || responseData.isBlank()) {
			return true;
		}
		return BAD_RESULT_KEYWORDS.stream().anyMatch(responseData::contains);
	}
}
