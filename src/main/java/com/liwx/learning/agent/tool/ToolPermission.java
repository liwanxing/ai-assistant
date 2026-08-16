package com.liwx.learning.agent.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 工具权限声明（类级注解）
 *
 * 标注了此注解的工具，只有拥有对应权限码的用户才能在工具候选池里看到它：
 *   未标注 → 公开工具，所有用户可检索
 *   标注   → 受控工具，Sa-Token 权限列表里含该权限码才召回
 *
 * 与 @SaCheckPermission 的关系：同一套权限码、两种拦截位置——
 *   @SaCheckPermission 挡 HTTP 接口层（人调接口）
 *   @ToolPermission   挡工具候选池（模型调工具，绕过接口层，必须再挡一次）
 *
 * 数据流：权限码写在注解上 → ToolRegistryService 启动时读进向量索引 metadata →
 *        检索时拼进 filterExpression，权限过滤和向量召回一次查询完成
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ToolPermission {

    /** 所需权限码，如 "user:list"（对应 sys_permission 表的 permission_code） */
    String value();
}
