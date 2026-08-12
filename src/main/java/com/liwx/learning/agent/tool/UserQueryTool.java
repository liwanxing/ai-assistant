package com.liwx.learning.agent.tool;

import com.liwx.learning.user.entity.User;
import com.liwx.learning.user.mapper.PermissionMapper;
import com.liwx.learning.user.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 用户信息查询工具
 *
 * 让 Agent 具备查询业务数据的能力：
 *   问"系统有多少用户"     → 模型调 getUserCount
 *   问"张三的邮箱是多少"   → 模型调 getUserDetail
 *   问"系统里有哪些用户"   → 模型调 listUsers
 *
 * 复用现有的 UserMapper + PermissionMapper，只查不改
 */
@Slf4j
@Component
public class UserQueryTool {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PermissionMapper permissionMapper;

    /**
     * 查询系统用户总数
     */
    @Tool(description = "查询系统注册用户总数和状态统计。当用户问系统有多少用户、有多少注册用户时调用此工具。")
    public String getUserCount() {
        log.info("UserQueryTool.getUserCount 被调用");
        List<User> users = userMapper.selectAll();
        long total = users.size();
        long active = users.stream().filter(u -> u.getStatus() != null && u.getStatus() == 1).count();
        long disabled = total - active;
        return String.format("系统共有 %d 个用户，其中正常 %d 个，禁用 %d 个。", total, active, disabled);
    }

    /**
     * 按用户名或昵称查用户详情（含角色和权限）
     */
    @Tool(description = "按用户名或昵称查询用户详细信息，包括邮箱、手机号、角色和权限。当用户问某个用户的邮箱、手机号、角色、权限等信息时调用此工具。")
    public String getUserDetail(
            @ToolParam(description = "用户名或昵称，如：admin、zhangsan、张三、李四") String name
    ) {
        log.info("UserQueryTool.getUserDetail 被调用，查询：{}", name);

        // 先按 username 精确查
        User user = userMapper.selectByUsername(name);
        // 查不到再按 nickname 匹配
        if (user == null) {
            List<User> all = userMapper.selectAll();
            user = all.stream()
                    .filter(u -> name.equals(u.getNickname()))
                    .findFirst()
                    .orElse(null);
        }
        if (user == null) {
            return "未找到用户：" + name;
        }

        // 查角色和权限
        List<String> roles = permissionMapper.selectRoleCodesByUserId(user.getId());
        List<String> permissions = permissionMapper.selectPermissionCodesByUserId(user.getId());

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("用户ID：%d\n", user.getId()));
        sb.append(String.format("用户名：%s\n", user.getUsername()));
        sb.append(String.format("昵称：%s\n", user.getNickname()));
        sb.append(String.format("邮箱：%s\n", user.getEmail()));
        sb.append(String.format("手机号：%s\n", user.getPhone()));
        sb.append(String.format("状态：%s\n", user.getStatus() != null && user.getStatus() == 1 ? "正常" : "禁用"));
        sb.append(String.format("创建时间：%s\n", user.getCreateTime()));
        sb.append(String.format("角色：%s\n", roles.isEmpty() ? "无" : String.join("、", roles)));
        sb.append(String.format("权限（%d个）：%s", permissions.size(),
                permissions.isEmpty() ? "无" : String.join("、", permissions)));

        return sb.toString();
    }

    /**
     * 列出所有用户的基本信息
     */
    @Tool(description = "列出系统所有用户的基本信息和角色。当用户问有哪些用户、用户列表、所有用户时调用此工具。")
    public String listUsers() {
        log.info("UserQueryTool.listUsers 被调用");
        List<User> users = userMapper.selectAll();
        if (users.isEmpty()) {
            return "系统中暂无用户";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("共 %d 个用户：\n", users.size()));
        for (User user : users) {
            List<String> roles = permissionMapper.selectRoleCodesByUserId(user.getId());
            sb.append(String.format("- %s（昵称：%s）| 邮箱：%s | 手机：%s | 状态：%s | 角色：%s",
                    user.getUsername(),
                    user.getNickname(),
                    user.getEmail(),
                    user.getPhone(),
                    user.getStatus() != null && user.getStatus() == 1 ? "正常" : "禁用",
                    roles.isEmpty() ? "无" : String.join("、", roles)
            ));
            sb.append("\n");
        }
        return sb.toString().trim();
    }
}
