package com.liwx.aiassistant.config;

import cn.dev33.satoken.stp.StpInterface;
import com.liwx.aiassistant.user.mapper.PermissionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Sa-Token 权限数据源
 *
 * 认证 vs 授权：
 *   认证（Authentication）= 你是谁？    → StpUtil.login(userId) 搞定了
 *   授权（Authorization） = 你能干什么？ → 这个类负责回答
 *
 * Sa-Token 在执行 @SaCheckRole / @SaCheckPermission 时会调这个类：
 *   "用户 1 有哪些角色？"   → 查 sys_user_role + sys_role → 返回 ["admin"]
 *   "用户 1 有哪些权限？" → 查 sys_user_role + sys_role + sys_role_permission + sys_permission → 返回 ["user:add", "user:delete"]
 *
 * 数据全在数据库里，改库里的角色/权限分配，接口的权限立即变化，不用改代码
 */
@Component
@RequiredArgsConstructor
public class StpInterfaceImpl implements StpInterface {

    private final PermissionMapper permissionMapper;

    /**
     * 返回指定用户的角色列表
     * Sa-Token 在执行 @SaCheckRole 时会调这个方法
     */
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        Long userId = Long.parseLong(loginId.toString());
        List<String> roleCodes = permissionMapper.selectRoleCodesByUserId(userId);
        return roleCodes != null ? roleCodes : List.of();
    }

    /**
     * 返回指定用户的权限列表
     * Sa-Token 在执行 @SaCheckPermission 时会调这个方法
     */
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        Long userId = Long.parseLong(loginId.toString());
        List<String> permissionCodes = permissionMapper.selectPermissionCodesByUserId(userId);
        return permissionCodes != null ? permissionCodes : List.of();
    }
}
