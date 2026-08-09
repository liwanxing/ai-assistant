package com.liwx.learning.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.liwx.learning.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 登录认证接口
 *
 * Sa-Token 的核心就是对一张"token 表"做增删改查：
 *   login()    → 新增一条 token-userId 记录（INSERT）
 *   checkLogin()→ 查 token 是否有效（SELECT，在拦截器里自动执行）
 *   getLoginId()→ 根据 token 查 userId（SELECT）
 *   logout()   → 删除这条 token 记录（DELETE）
 *
 * 现在存在内存里，重启就清空；以后换 Redis 存，重启也不丢
 */
@RestController
public class AuthController {

    /**
     * 登录：验证账号密码，成功后 Sa-Token 自动生成 token
     * 登录后调用 StpUtil.getTokenValue() 就能拿到当前会话的 token
     */
    @GetMapping("/login")
    public Result<Map<String, String>> login(@RequestParam String username, @RequestParam String password) {
        // 暂时写死账号密码，后面接数据库
        if ("admin".equals(username) && "123456".equals(password)) {
            // Sa-Token 登录：传入用户ID，框架自动生成 token
            StpUtil.login(1L);
            // 登录后可以拿到 token
            Map<String, String> data = new HashMap<>();
            data.put("tokenName", StpUtil.getTokenName());
            data.put("tokenValue", StpUtil.getTokenValue());
            return Result.success(data);
        }
        return Result.error(401, "账号或密码错误");
    }

    /**
     * 登出：销毁当前会话的 token，之前发的 token 立刻失效
     */
    @GetMapping("/logout")
    public Result<Void> logout() {
        StpUtil.logout();
        return Result.success();
    }

    /**
     * 获取当前登录用户ID
     * StpUtil.getLoginId() 会从 token 反查到对应的用户ID
     */
    @GetMapping("/me")
    public Result<Long> currentUser() {
        return Result.success(StpUtil.getLoginIdAsLong());
    }
}
