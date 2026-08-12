package com.liwx.learning.user.dto;

import lombok.Data;

/**
 * 登录请求参数
 * 用 POST + @RequestBody 接收，密码不会出现在 URL 里
 */
@Data
public class LoginDTO {
    private String username;
    private String password;
}
