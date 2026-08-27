package com.liwx.aiassistant.rag.mq;

/**
 * 文档处理消息体：上传接口（生产者）→ MQ → 消费端 的全部参数
 *
 * 用 record：纯数据载体，不可变，天生适合做消息。
 * splitStrategy 存字符串而不是枚举：跨进程序列化后消费端 valueOf 还原，
 * 字符串直观（Dashboard 里能直接看懂），也不耦合两端的枚举定义
 *
 * 只带 documentId 不行吗？不行——文件路径、切分策略都在上传请求里，
 * 消费端只拿到消息（拿不到 HTTP 上下文），需要的字段必须全部随消息走
 */
public record DocumentProcessMessage(Long documentId, String filePath, String splitStrategy) {
}
