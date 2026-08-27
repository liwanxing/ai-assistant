package com.liwx.aiassistant.user.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.liwx.aiassistant.common.Result;
import com.liwx.aiassistant.user.dto.LoginDTO;
import com.liwx.aiassistant.user.entity.User;
import com.liwx.aiassistant.user.mapper.UserMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final PasswordEncoder passwordEncoder;

    public AuthController(UserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 登录：查数据库验证账号密码，成功后 Sa-Token 自动生成 token
     * 用 POST：密码在请求体里，不会出现在 URL、浏览器历史、服务器日志中
     */
    @PostMapping("/login")
    public Result<Map<String, String>> login(@RequestBody LoginDTO loginDTO) {
        User user = userMapper.selectByUsername(loginDTO.getUsername());
        if (user == null) {
            return Result.error(401, "账号或密码错误");
        }
        // BCrypt 校验：matches(明文, 哈希值)
        if (!passwordEncoder.matches(loginDTO.getPassword(), user.getPassword())) {
            return Result.error(401, "账号或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            return Result.error(403, "账号已被禁用");
        }
        StpUtil.login(user.getId());
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
