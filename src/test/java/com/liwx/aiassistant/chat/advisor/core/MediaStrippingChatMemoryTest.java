package com.liwx.aiassistant.chat.advisor.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MediaStrippingChatMemory 纯单元测试（Mockito mock 内层 ChatMemory，不启动 Spring）
 *
 * 验证装饰器核心行为：写入前剥离多模态消息里的 base64 图片，只保留文本——
 * 数据库不膨胀、旧图片不重复进上下文；纯文本消息与非 UserMessage 原样透传
 */
class MediaStrippingChatMemoryTest {

    private ChatMemory delegate;
    private MediaStrippingChatMemory stripping;

    @BeforeEach
    void setUp() {
        delegate = mock(ChatMemory.class);
        stripping = new MediaStrippingChatMemory(delegate);
    }

    /**
     * 构造带图片的多模态 UserMessage（模拟用户上传图片对话的消息形态）
     */
    private UserMessage multimodalMessage(String text) {
        return UserMessage.builder()
                .text(text)
                .media(new Media(Media.Format.IMAGE_PNG, new ByteArrayResource("fake-image-bytes".getBytes())))
                .build();
    }

    @Test
    void shouldStripMediaFromMultimodalUserMessage() {
        stripping.add("s1", List.of(multimodalMessage("看这张图")));

        // 捕获真正转发给内层（最终落库）的消息列表
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.captor();
        verify(delegate).add(eq("s1"), captor.capture());
        List<Message> saved = captor.getValue();

        assertEquals(1, saved.size());
        assertInstanceOf(UserMessage.class, saved.get(0));
        // 核心断言：媒体被剥离（getMedia 为空）、文本保留
        assertTrue(((UserMessage) saved.get(0)).getMedia().isEmpty());
        assertEquals("看这张图", saved.get(0).getText());
    }

    @Test
    void shouldKeepPlainTextUserMessageAsIs() {
        UserMessage textOnly = new UserMessage("纯文本问题");

        stripping.add("s1", List.of(textOnly));

        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.captor();
        verify(delegate).add(eq("s1"), captor.capture());
        // 无媒体的消息不走重建逻辑：原对象直接透传（assertSame 验证是同一个实例）
        assertSame(textOnly, captor.getValue().get(0));
    }

    @Test
    void shouldKeepAssistantMessageAsIs() {
        AssistantMessage reply = new AssistantMessage("模型回答");

        stripping.add("s1", List.of(reply));

        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.captor();
        verify(delegate).add(eq("s1"), captor.capture());
        // AssistantMessage 天然无媒体，原样透传
        assertSame(reply, captor.getValue().get(0));
    }

    @Test
    void shouldKeepSystemMessageAsIs() {
        SystemMessage system = new SystemMessage("system-prompt");

        stripping.add("s1", List.of(system));

        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.captor();
        verify(delegate).add(eq("s1"), captor.capture());
        assertSame(system, captor.getValue().get(0));
    }

    @Test
    void shouldPassThroughGetAndClear() {
        when(delegate.get("s1")).thenReturn(List.of());

        assertEquals(List.of(), stripping.get("s1"));

        stripping.clear("s1");
        verify(delegate).clear("s1");
    }
}
