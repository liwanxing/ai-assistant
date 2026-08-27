package com.liwx.aiassistant.user.dto;

import jakarta.validation.constraints.Email;
import lombok.Data;

/**
 * 更新用户请求 DTO
 * 更新时不传用户名和密码，只改昵称、邮箱、手机号
 */
@Data
public class UserUpdateDTO {

    private String nickname;

    @Email(message = "邮箱格式不正确")
    private String email;

    private String phone;
}
