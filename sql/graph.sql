-- ============================================================
-- 智能经营分析系统 - 数据库初始化脚本（graph-learning-java 项目用）
-- 数据库：ai_assistant（与主项目共享同一个库）
--
-- 4 张表供 Graph 工作流做多步分析演示：
--   product（商品）→ customer（用户）→ orders（订单）→ order_item（订单明细）
--   关联链：customer(1) → orders(N) → order_item(N) → product(1)
-- ============================================================

USE ai_assistant;

-- ============================================================
-- 1. 商品表
-- ============================================================
DROP TABLE IF EXISTS `product`;
CREATE TABLE `product` (
    `id`          BIGINT          NOT NULL COMMENT '商品ID',
    `name`        VARCHAR(100)    NOT NULL COMMENT '商品名称',
    `category`    VARCHAR(50)     NOT NULL COMMENT '类目：electronics/clothing/food',
    `price`       DECIMAL(10,2)   NOT NULL COMMENT '单价',
    `stock`       INT             NOT NULL DEFAULT 0 COMMENT '库存',
    `status`      VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE上架/OFF下架',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品表';

-- ============================================================
-- 2. 用户表
-- ============================================================
DROP TABLE IF EXISTS `customer`;
CREATE TABLE `customer` (
    `id`            BIGINT          NOT NULL COMMENT '用户ID',
    `name`          VARCHAR(50)     NOT NULL COMMENT '用户名',
    `vip_level`     INT             NOT NULL DEFAULT 0 COMMENT '0普通 1白银 2黄金 3钻石',
    `register_date` DATE            NOT NULL COMMENT '注册日期',
    `status`        VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE活跃/DORMANT沉睡',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- ============================================================
-- 3. 订单表
-- ============================================================
DROP TABLE IF EXISTS `orders`;
CREATE TABLE `orders` (
    `id`           BIGINT          NOT NULL COMMENT '订单ID',
    `customer_id`  BIGINT          NOT NULL COMMENT '用户ID',
    `total_amount` DECIMAL(10,2)   NOT NULL COMMENT '订单总额',
    `status`       VARCHAR(20)     NOT NULL COMMENT 'PAID已付/COMPLETED完成/REFUNDED已退',
    `create_time`  DATETIME        NOT NULL COMMENT '下单时间',
    PRIMARY KEY (`id`),
    KEY `idx_customer` (`customer_id`),
    KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';

-- ============================================================
-- 4. 订单明细表
-- ============================================================
DROP TABLE IF EXISTS `order_item`;
CREATE TABLE `order_item` (
    `id`          BIGINT          NOT NULL AUTO_INCREMENT COMMENT '明细ID',
    `order_id`    BIGINT          NOT NULL COMMENT '订单ID',
    `product_id`  BIGINT          NOT NULL COMMENT '商品ID',
    `quantity`    INT             NOT NULL COMMENT '购买数量',
    `unit_price`  DECIMAL(10,2)   NOT NULL COMMENT '成交单价',
    PRIMARY KEY (`id`),
    KEY `idx_order` (`order_id`),
    KEY `idx_product` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单明细表';


-- ============================================================
-- 测试数据
-- ============================================================

-- 商品数据（8个商品，3个类目，有畅销有滞销）
INSERT INTO `product` (`id`, `name`, `category`, `price`, `stock`, `status`) VALUES
(1001, '蓝牙耳机Pro',  'electronics', 299.00,  50, 'ACTIVE'),
(1002, '手机壳',       'electronics',  29.90, 500, 'ACTIVE'),
(1003, '智能手表',     'electronics', 899.00,  30, 'ACTIVE'),
(1004, '纯棉T恤',      'clothing',     79.00, 200, 'ACTIVE'),
(1005, '牛仔裤',       'clothing',    199.00,   0, 'ACTIVE'),  -- 缺货！
(1006, '羽绒服',       'clothing',    599.00,  10, 'ACTIVE'),
(1007, '坚果礼包',     'food',         49.90, 300, 'ACTIVE'),
(1008, '进口咖啡豆',   'food',         89.00, 150, 'ACTIVE');

-- 用户数据（6个用户，不同VIP等级）
INSERT INTO `customer` (`id`, `name`, `vip_level`, `register_date`, `status`) VALUES
(1, '张伟',   3, '2023-01-15', 'ACTIVE'),   -- 钻石老用户
(2, '李娜',   2, '2023-06-20', 'ACTIVE'),   -- 黄金用户
(3, '王芳',   1, '2024-03-10', 'ACTIVE'),   -- 白银用户
(4, '刘洋',   0, '2024-11-01', 'ACTIVE'),   -- 新用户
(5, '陈静',   0, '2024-12-15', 'ACTIVE'),   -- 新用户
(6, '赵强',   2, '2023-08-05', 'DORMANT');  -- 沉睡黄金用户

-- 订单数据（12个订单，分布在不同时间段）
INSERT INTO `orders` (`id`, `customer_id`, `total_amount`, `status`, `create_time`) VALUES
-- 上月订单
(5001, 1, 1198.00, 'COMPLETED', '2025-07-05 10:30:00'),  -- 蓝牙耳机+智能手表
(5002, 2,  199.00, 'COMPLETED', '2025-07-08 14:20:00'),  -- 牛仔裤（当时有货）
(5003, 3,  129.00, 'COMPLETED', '2025-07-12 09:15:00'),  -- 纯棉T恤+手机壳
(5004, 1,  599.00, 'COMPLETED', '2025-07-20 16:45:00'),  -- 羽绒服
(5005, 4,   49.90, 'COMPLETED', '2025-07-25 11:00:00'),  -- 坚果礼包（新用户首单）
-- 本月订单（销量明显下降）
(5006, 1,  299.00, 'PAID',      '2025-08-02 10:30:00'),  -- 蓝牙耳机
(5007, 2,  179.00, 'COMPLETED', '2025-08-05 13:20:00'),  -- 坚果礼包+咖啡豆
(5008, 5,   79.00, 'PAID',      '2025-08-08 15:00:00'),  -- 纯棉T恤（新用户）
(5009, 3,   89.00, 'REFUNDED',  '2025-08-10 09:30:00'),  -- 咖啡豆（退款！）
(5010, 1,  149.90, 'COMPLETED', '2025-08-12 17:45:00'),  -- 坚果礼包+手机壳
-- 补充历史订单
(5011, 2,  899.00, 'COMPLETED', '2025-06-15 14:00:00'),  -- 智能手表
(5012, 6,  199.00, 'COMPLETED', '2025-05-20 10:10:00');  -- 牛仔裤（沉睡用户最后订单）

-- 订单明细数据
INSERT INTO `order_item` (`order_id`, `product_id`, `quantity`, `unit_price`) VALUES
-- 订单5001：蓝牙耳机+智能手表
(5001, 1001, 1, 299.00),
(5001, 1003, 1, 899.00),
-- 订单5002：牛仔裤
(5002, 1005, 1, 199.00),
-- 订单5003：纯棉T恤+手机壳
(5003, 1004, 1, 79.00),
(5003, 1002, 1, 29.90),
(5003, 1007, 1, 49.90),
-- 订单5004：羽绒服
(5004, 1006, 1, 599.00),
-- 订单5005：坚果礼包
(5005, 1007, 1, 49.90),
-- 订单5006：蓝牙耳机
(5006, 1001, 1, 299.00),
-- 订单5007：坚果礼包+咖啡豆
(5007, 1007, 1, 49.90),
(5007, 1008, 1, 89.00),
-- 订单5008：纯棉T恤
(5008, 1004, 1, 79.00),
-- 订单5009：咖啡豆（已退款）
(5009, 1008, 1, 89.00),
-- 订单5010：坚果礼包+手机壳
(5010, 1007, 2, 49.90),
(5010, 1002, 1, 29.90),
-- 订单5011：智能手表
(5011, 1003, 1, 899.00),
-- 订单5012：牛仔裤
(5012, 1005, 1, 199.00);
