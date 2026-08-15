package com.liwx.learning.config;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

import java.util.ArrayList;
import java.util.List;

/**
 * 读取截断 ChatMemory 包装器（存用分离）
 *
 * 问题：MessageWindowChatMemory 把「存多少」和「模型看多少」绑死——
 *       maxMessages=30 时数据库物理只剩 30 条（≈15 轮），用户点开历史会话，
 *       前面的对话凭空消失；而模型上下文本来只靠 摘要+20条 就够，
 *       存储被上下文策略绑架了。
 *
 * 解决：装饰 get()——写入全量透传（存储窗口由内层 MessageWindowChatMemory 的
 *       maxMessages=500 控制，≈250 轮，供历史回看），读取时只返回最近 limit 条
 *       给模型上下文（外层摘要 Advisor 再压成 摘要 + 最近 20 条）。
 *
 * 三个数字各司其职：
 *   500 = 存多少（物理上限，DB 里的对话档案）
 *    30 = 读多少（模型上下文窗口 = 摘要 Advisor 的缓冲区：20 保留 + 10 溢出压缩）
 *    20 = 摘要后保留多少条原文
 *
 * 只在 ChatClient 链路（MessageChatMemoryAdvisor）里包这一层，
 * /messages 回看接口注入的是未包装的 Bean，拿到的是全量历史。
 *
 * 面试一句话：用装饰器模式实现记忆的存用分离——数据库是完整档案，
 * 模型上下文是滑动窗口，两者互不绑架
 */
public class ReadLimitChatMemory implements ChatMemory {

    private final ChatMemory delegate;
    private final int limit;

    public ReadLimitChatMemory(ChatMemory delegate, int limit) {
        this.delegate = delegate;
        this.limit = limit;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        // 写入不截断：全量进存储，存多少由内层窗口决定
        delegate.add(conversationId, messages);
    }

    @Override
    public List<Message> get(String conversationId) {
        List<Message> all = delegate.get(conversationId);

        // SYSTEM 消息不参与截断（若存在则永远保留在最前，防止系统提示词被窗口挤掉）
        List<Message> systems = new ArrayList<>();
        List<Message> others = new ArrayList<>();
        for (Message msg : all) {
            if (msg.getMessageType() == MessageType.SYSTEM) {
                systems.add(msg);
            } else {
                others.add(msg);
            }
        }

        if (others.size() <= limit) {
            return all;
        }

        // 只取最近 limit 条（消息按时间正序存储，尾部即最新）
        List<Message> result = new ArrayList<>(systems);
        result.addAll(others.subList(others.size() - limit, others.size()));
        return result;
    }

    @Override
    public void clear(String conversationId) {
        delegate.clear(conversationId);
    }
}
