package com.liwx.aiassistant.chat.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.liwx.aiassistant.common.Result;
import com.liwx.aiassistant.chat.entity.UserMemory;
import com.liwx.aiassistant.chat.advisor.core.UserMemoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 用户长期记忆管理接口
 *
 * 前端管理页面用：查看/修改/删除当前登录用户的记忆
 * 修改和删除都会同步向量库（Milvus）
 */
@RestController
@RequestMapping("/memory")
@RequiredArgsConstructor
public class MemoryController {

    private final UserMemoryService userMemoryService;

    /**
     * 查询当前用户的所有记忆
     */
    @GetMapping("/list")
    public Result<List<UserMemory>> list() {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.success(userMemoryService.listByUserId(userId));
    }

    /**
     * 修改记忆内容（同步 Milvus）
     */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String content = body.get("content");
        if (content == null || content.isBlank()) {
            return Result.error(400, "记忆内容不能为空");
        }
        userMemoryService.updateMemory(id, content.trim());
        return Result.success();
    }

    /**
     * 删除记忆（同步 Milvus）
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        userMemoryService.deleteMemory(id);
        return Result.success();
    }
}
