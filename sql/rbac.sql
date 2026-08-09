-- =============================================
-- RBAC 权限模型完整建表脚本
-- 包含 5 张表 + 测试数据，可在 DBeaver 中直接执行
-- =============================================

USE liwx_learning;

-- 先删后建（方便重复执行，按依赖顺序倒着删）
DROP TABLE IF EXISTS sys_role_permission;
DROP TABLE IF EXISTS sys_user_role;
DROP TABLE IF EXISTS sys_permission;
DROP TABLE IF EXISTS sys_role;
DROP TABLE IF EXISTS sys_user;

-- 1. 用户表
CREATE TABLE sys_user (
    id          BIGINT       PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    username    VARCHAR(50)  NOT NULL                COMMENT '用户名',
    password    VARCHAR(100) NOT NULL                COMMENT '密码',
    nickname    VARCHAR(50)           DEFAULT NULL    COMMENT '昵称',
    email       VARCHAR(100)          DEFAULT NULL    COMMENT '邮箱',
    phone       VARCHAR(20)           DEFAULT NULL    COMMENT '手机号',
    status      TINYINT      NOT NULL DEFAULT 1      COMMENT '状态 1正常 0禁用',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除 0未删除 1已删除'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 2. 角色表
CREATE TABLE sys_role (
    id          BIGINT       PRIMARY KEY AUTO_INCREMENT COMMENT '角色ID',
    role_name   VARCHAR(50)  NOT NULL                COMMENT '角色名称（展示用）：管理员、编辑者',
    role_code   VARCHAR(50)  NOT NULL                COMMENT '角色编码（代码里用）：admin、editor',
    sort        INT          NOT NULL DEFAULT 0      COMMENT '排序号（越小越靠前）',
    status      TINYINT      NOT NULL DEFAULT 1      COMMENT '状态 1正常 0禁用',
    remark      VARCHAR(200)          DEFAULT NULL    COMMENT '备注',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除 0未删除 1已删除',
    UNIQUE KEY uk_role_code (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色表';

-- 3. 权限表（树形结构，菜单和按钮都放这里）
CREATE TABLE sys_permission (
    id              BIGINT       PRIMARY KEY AUTO_INCREMENT COMMENT '权限ID',
    parent_id       BIGINT       NOT NULL DEFAULT 0      COMMENT '父权限ID（0表示顶层）',
    permission_name VARCHAR(50)  NOT NULL                COMMENT '权限名称（展示用）：删除用户',
    permission_code VARCHAR(100) NOT NULL                COMMENT '权限编码（代码里用）：user:delete',
    type            TINYINT      NOT NULL DEFAULT 1      COMMENT '类型 0菜单 1按钮（菜单是页面级，按钮是操作级）',
    path            VARCHAR(200)          DEFAULT NULL    COMMENT '前端路由地址（菜单类型才有）',
    sort            INT          NOT NULL DEFAULT 0      COMMENT '排序号',
    status          TINYINT      NOT NULL DEFAULT 1      COMMENT '状态 1正常 0禁用',
    remark          VARCHAR(200)          DEFAULT NULL    COMMENT '备注',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除 0未删除 1已删除',
    UNIQUE KEY uk_permission_code (permission_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权限表';

-- 4. 用户-角色关联表（多对多）
CREATE TABLE sys_user_role (
    id          BIGINT   PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    user_id     BIGINT   NOT NULL                COMMENT '用户ID',
    role_id     BIGINT   NOT NULL                COMMENT '角色ID',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    KEY idx_user_id (user_id),
    KEY idx_role_id (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户角色关联表';

-- 5. 角色-权限关联表（多对多）
CREATE TABLE sys_role_permission (
    id            BIGINT   PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    role_id       BIGINT   NOT NULL                COMMENT '角色ID',
    permission_id BIGINT   NOT NULL                COMMENT '权限ID',
    create_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    KEY idx_role_id (role_id),
    KEY idx_permission_id (permission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色权限关联表';


-- =============================================
-- 测试数据
-- =============================================

-- 用户：3个，分别对应 3 种角色
INSERT INTO sys_user (username, password, nickname, email, phone) VALUES
('admin',     '123456', '管理员', 'admin@test.com',     '13800000000'),
('zhangsan',  '123456', '张三',   'zhangsan@test.com',  '13800000001'),
('lisi',      '123456', '李四',   'lisi@test.com',      '13800000002');

-- 角色：3个
INSERT INTO sys_role (role_name, role_code, sort, remark) VALUES
('管理员', 'admin',  1, '系统管理员，拥有所有权限'),
('编辑者', 'editor', 2, '编辑者，能增删改用户但不能管理角色'),
('访客',   'viewer', 3, '只读用户，只能查看');

-- 权限：3个菜单（type=0）
INSERT INTO sys_permission (parent_id, permission_name, permission_code, type, path, sort) VALUES
(0, '用户管理', 'user',   0, '/users',  1),  -- id=1
(0, '角色管理', 'role',   0, '/roles',  2),  -- id=2
(0, '系统设置', 'system', 0, '/system', 3);  -- id=3

-- 9个按钮（type=1），parent_id 指向所属菜单
INSERT INTO sys_permission (parent_id, permission_name, permission_code, type, sort) VALUES
(1, '查询用户', 'user:list',     1, 1),  -- id=4
(1, '新增用户', 'user:add',      1, 2),  -- id=5
(1, '修改用户', 'user:edit',     1, 3),  -- id=6
(1, '删除用户', 'user:delete',   1, 4),  -- id=7
(1, '导出用户', 'user:export',   1, 5),  -- id=8
(2, '查询角色', 'role:list',     1, 1),  -- id=9
(2, '新增角色', 'role:add',      1, 2),  -- id=10
(2, '删除角色', 'role:delete',   1, 3),  -- id=11
(3, '系统监控', 'system:monitor', 1, 1); -- id=12

-- 用户-角色分配
-- admin(id=1) → 管理员，zhangsan(id=2) → 编辑者，lisi(id=3) → 访客
INSERT INTO sys_user_role (user_id, role_id) VALUES
(1, 1),
(2, 2),
(3, 3);

-- 角色-权限分配
-- 管理员(role_id=1) 拥有全部权限
INSERT INTO sys_role_permission (role_id, permission_id) VALUES
(1, 1), (1, 2), (1, 3),
(1, 4), (1, 5), (1, 6), (1, 7), (1, 8),
(1, 9), (1, 10), (1, 11),
(1, 12);

-- 编辑者(role_id=2) 只能查看+新增+修改用户，不能删除+导出+管角色
INSERT INTO sys_role_permission (role_id, permission_id) VALUES
(2, 1),
(2, 4), (2, 5), (2, 6);

-- 访客(role_id=3) 只能查看用户
INSERT INTO sys_role_permission (role_id, permission_id) VALUES
(3, 1),
(3, 4);
