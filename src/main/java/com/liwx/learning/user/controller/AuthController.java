package com.liwx.learning.user.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.liwx.learning.common.Result;
import com.liwx.learning.user.entity.User;
import com.liwx.learning.user.mapper.UserMapper;
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

    private final UserMapper userMapper;

    public AuthController(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /**
     * 登录：查数据库验证账号密码，成功后 Sa-Token 自动生成 token
     */
    @GetMapping("/login")
    public Result<Map<String, String>> login(@RequestParam String username, @RequestParam String password) {
        // 1. 查数据库：根据用户名找用户
        User user = userMapper.selectByUsername(username);
        // 2. 用户不存在或密码不对
        if (user == null || !user.getPassword().equals(password)) {
            return Result.error(401, "账号或密码错误");
        }
        // 3. 账号被禁用
        if (user.getStatus() != null && user.getStatus() == 0) {
            return Result.error(403, "账号已被禁用");
        }
        // 4. Sa-Token 登录：传入用户ID，框架自动生成 token
        StpUtil.login(user.getId());
        // 5. 返回 token 给前端
        Map<String, String> data = new HashMap<>();
        data.put("tokenName", StpUtil.getTokenName());
        data.put("tokenValue", StpUtil.getTokenValue());
        return Result.success(data);
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
