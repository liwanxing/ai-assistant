package com.liwx.aiassistant.user.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import com.liwx.aiassistant.common.Result;
import com.liwx.aiassistant.user.dto.UserCreateDTO;
import com.liwx.aiassistant.user.dto.UserUpdateDTO;
import com.liwx.aiassistant.user.entity.User;
import com.liwx.aiassistant.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    // GET /users - 查询所有用户（需要 user:list 权限）
    @SaCheckPermission("user:list")
    @GetMapping
    public Result<List<User>> getAllUsers() {
        return Result.success(userService.getAllUsers());
    }

    // GET /users/{id} - 查询单个用户（需要 user:list 权限）
    @SaCheckPermission("user:list")
    @GetMapping("/{id}")
    public Result<User> getUserById(@PathVariable Long id) {
        return Result.success(userService.getUserById(id));
    }

    // POST /users - 创建用户（需要 user:add 权限）
    @SaCheckPermission("user:add")
    @PostMapping
    public Result<User> createUser(@Valid @RequestBody UserCreateDTO dto) {
        return Result.success(userService.createUser(dto));
    }

    // PUT /users/{id} - 更新用户（需要 user:edit 权限）
    @SaCheckPermission("user:edit")
    @PutMapping("/{id}")
    public Result<User> updateUser(@PathVariable Long id, @Valid @RequestBody UserUpdateDTO dto) {
        return Result.success(userService.updateUser(id, dto));
    }

    // DELETE /users/{id} - 删除用户（需要 admin 角色 + user:delete 权限）
    @SaCheckRole("admin")
    @SaCheckPermission("user:delete")
    @DeleteMapping("/{id}")
    public Result<Void> deleteUser(@PathVariable Long id) {
        userService.deleteUser(id);
        return Result.success();
    }
}
