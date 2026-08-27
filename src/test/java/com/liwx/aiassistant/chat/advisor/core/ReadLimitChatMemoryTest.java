package com.liwx.aiassistant.chat.advisor.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReadLimitChatMemory 纯单元测试（Mockito mock 内层 ChatMemory，不启动 Spring、不连数据库）
 *
 * 验证装饰器的三个核心行为：
 * 1. 写入全量透传（存多少由内层决定，外层不管）
 * 2. 读取超限截断取尾部（尾部即最新消息）
 * 3. SYSTEM 消息豁免（系统提示词永远保留，不被窗口挤掉）
 */
class ReadLimitChatMemoryTest {

    private ChatMemory delegate;
    private ReadLimitChatMemory readLimit;

    @BeforeEach
    void setUp() {
        delegate = mock(ChatMemory.class);
        readLimit = new ReadLimitChatMemory(delegate, 5);
    }

    /**
     * 造 n 条非 SYSTEM 消息（user/assistant 交替，内容带序号便于断言"保留了哪几条"）
     */
    private List<Message> messages(int n) {
        List<Message> list = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            list.add(i % 2 == 1 ? new UserMessage("user-" + i) : new AssistantMessage("assistant-" + i));
        }
        return list;
    }

    @Test
    void shouldPassThroughWriteWithoutTruncation() {
        List<Message> batch = messages(50);

        readLimit.add("s1", batch);

        // 写入是全量透传：50 条原样转发给内层——截断只发生在读取侧（存用分离的"存"）
        verify(delegate).add(eq("s1"), eq(batch));
    }

    @Test
    void shouldReturnAllWhenWithinLimit() {
        List<Message> three = messages(3);
        when(delegate.get("s1")).thenReturn(three);

        List<Message> result = readLimit.get("s1");

        // 3 条 < limit 5：不触发截断，原样返回
        assertEquals(3, result.size());
        assertEquals(three, result);
    }

    @Test
    void shouldReturnExactlyLimitWhenEqual() {
        when(delegate.get("s1")).thenReturn(messages(5));

        // 5 条 == limit 5：边界情况，不截断也不丢消息
        assertEquals(5, readLimit.get("s1").size());
    }

    @Test
    void shouldTruncateToTailWhenExceedsLimit() {
        when(delegate.get("s1")).thenReturn(messages(20));

        List<Message> result = readLimit.get("s1");

        assertEquals(5, result.size());
        // 消息按时间正序存储，尾部即最新：应保留第 16~20 条（16 为偶数，辅助方法生成的是 assistant-16）
        assertEquals("assistant-16", result.get(0).getText());
        assertEquals("assistant-20", result.get(4).getText());
    }

    @Test
    void shouldKeepSystemMessageAlways() {
        List<Message> all = new ArrayList<>();
        all.add(new SystemMessage("system-prompt"));
        all.addAll(messages(20));
        when(delegate.get("s1")).thenReturn(all);

        List<Message> result = readLimit.get("s1");

        // SYSTEM 不参与截断：1 条系统提示 + 最近 5 条 = 6 条，且 SYSTEM 永远在最前
        assertEquals(6, result.size());
        assertEquals(MessageType.SYSTEM, result.get(0).getMessageType());
        assertEquals("assistant-16", result.get(1).getText());
    }

    @Test
    void shouldReturnEmptyWhenNoHistory() {
        when(delegate.get("s1")).thenReturn(List.of());

        assertTrue(readLimit.get("s1").isEmpty());
    }

    @Test
    void shouldPassThroughClear() {
        readLimit.clear("s1");

        verify(delegate).clear("s1");
    }
}
