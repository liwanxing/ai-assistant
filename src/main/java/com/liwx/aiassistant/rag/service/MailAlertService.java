package com.liwx.aiassistant.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * 邮件告警服务：把"需要人知道的事"（目前是文档处理超时）推到邮箱
 *
 * 设计原则——告警通道绝不能反噬主流程：
 *   1. 开关 + 判空前置：rag.alert.enabled=false 或没配收件人时直接跳过（本地没配邮箱也能跑）
 *   2. 全量 try-catch：邮件发失败只打 error 日志不上抛——调用方是定时任务，
 *      邮件崩了把状态回滚毫无意义（判 FAILED 已经落库），把任务搞挂反而丢掉本轮其他告警
 *
 * 163 邮箱两个已知坑：
 *   - from 必须与认证账号（username）完全一致，否则服务端直接拒收（554）
 *   - password 是"授权码"不是登录密码：163 后台 → 设置 → POP3/SMTP → 开启 SMTP 后生成
 *
 * 为什么用 MimeMessageHelper 而不是更简单的 SimpleMailMessage：
 *   SimpleMailMessage 用平台默认编码，Windows 上是 GBK，中文主题/正文可能乱码；
 *   Helper 显式指定 UTF-8，顺手还留了升级 HTML 邮件的口子（multipart 参数）
 */
@Slf4j
@Service
public class MailAlertService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String from;

    @Value("${rag.alert.enabled:false}")
    private boolean enabled;

    @Value("${rag.alert.mail-to:}")
    private String mailTo;

    public MailAlertService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /**
     * 发送纯文本告警邮件（失败只记日志不上抛，理由见类注释）
     *
     * @param subject 主题（会自动加【RAG告警】前缀，收件箱里一眼可辨）
     * @param content 正文，纯文本
     */
    public void send(String subject, String content) {
        if (!enabled || mailTo == null || mailTo.isBlank()) {
            log.debug("告警邮件未启用（rag.alert.enabled=false 或未配置收件人），跳过发送: {}", subject);
            return;
        }
        try {
            var message = mailSender.createMimeMessage();
            // multipart=false 纯文本；UTF-8 防 Windows 平台 GBK 乱码
            var helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(mailTo.split(","));   // 逗号分隔多个收件人
            helper.setSubject("【RAG告警】" + subject);
            helper.setText(content);
            mailSender.send(message);
            log.info("告警邮件已发送: {} -> {}", subject, mailTo);
        } catch (Exception e) {
            log.error("告警邮件发送失败（不影响主流程）: subject={}, error={}", subject, e.getMessage());
        }
    }
}
