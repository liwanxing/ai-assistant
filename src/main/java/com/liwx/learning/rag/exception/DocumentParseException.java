package com.liwx.learning.rag.exception;

/**
 * 确定性解析失败（毒消息）：文件损坏 / 加密 / 不存在——重试一万次结果也不会变
 *
 * 与暂时性失败（网络抖动、Milvus/MySQL 挂）的本质区别：
 *   暂时性失败等一等可能自愈，值得交给 Broker 重投；
 *   本异常发生在"读磁盘上的静态文件"这一步，文件不会自己变好，重试纯属空转
 *
 * 抛出它 = 告诉调用方"别重试了，直接标 FAILED"：
 *   MQ 路径：Consumer 捕获后当场标 FAILED 并正常返回（ACK），不重投、不进死信——
 *            否则毒消息要空转 16 次重投（约 5 小时）才进死信，前端一直转圈
 *   @Async 路径：重试循环捕获后立即退出，与 MQ 路径行为对称
 *
 * 继承 RuntimeException 的理由同 BusinessException：调用链不用到处写 throws
 */
public class DocumentParseException extends RuntimeException {

    public DocumentParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
