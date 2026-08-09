package com.liwx.learning.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 权限 Mapper：查询用户的角色和权限
 *
 * 查询链路：
 *   角色：sys_user_role → sys_role（拿到 role_code）
 *   权限：sys_user_role → sys_role → sys_role_permission → sys_permission（拿到 permission_code）
 */
@Mapper
public interface PermissionMapper {

    /**
     * 根据用户ID查询角色编码列表
     * 只查状态正常、未删除的角色
     */
    List<String> selectRoleCodesByUserId(@Param("userId") Long userId);

    /**
     * 根据用户ID查询权限编码列表
     * 通过用户→角色→权限三层关联查询
     */
    List<String> selectPermissionCodesByUserId(@Param("userId") Long userId);
}
