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
}
