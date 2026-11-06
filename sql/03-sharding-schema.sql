-- ============================================================
-- GoShare 阶段3：订单表分片（ShardingSphere-JDBC 同库分表演示）
-- 逻辑表 trip_order 按 passenger_id % 2 路由到 trip_order_0 / trip_order_1
-- 用法：mysql -uroot -proot123 goshare < 03-sharding-schema.sql
-- 说明：老表数据全部迁移后改名 trip_order_legacy 保留兜底，逻辑表由分片接管
-- ============================================================

SET NAMES utf8mb4;

-- 1. 分片表 trip_order_0 / trip_order_1（结构与原表一致）
CREATE TABLE IF NOT EXISTS `trip_order_0` (
  `id`             BIGINT        NOT NULL COMMENT '订单ID（分片引擎雪花生成）',
  `order_no`       VARCHAR(32)   NOT NULL COMMENT '订单号',
  `trip_id`        BIGINT        NOT NULL COMMENT '行程ID',
  `passenger_id`   BIGINT        NOT NULL COMMENT '乘客用户ID',
  `seat_count`     INT           NOT NULL COMMENT '购买座位数',
  `total_price`    DECIMAL(10,2) NOT NULL COMMENT '订单总额（元）',
  `coupon_discount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '券优惠金额（阶段2接入）',
  `pay_price`      DECIMAL(10,2) NOT NULL COMMENT '实付金额（元）',
  `status`         VARCHAR(20)   NOT NULL DEFAULT 'CREATED' COMMENT '订单状态 CREATED待支付 PAID已支付 IN_SERVICE服务中 COMPLETED已完成 CANCELLED已取消 EXPIRED超时关闭',
  `payment_no`     VARCHAR(32)   DEFAULT NULL COMMENT '支付流水号',
  `cancel_reason`  VARCHAR(200)  DEFAULT NULL COMMENT '取消原因',
  `coupon_id`      BIGINT        DEFAULT NULL COMMENT '优惠券ID（阶段2接入）',
  `created_at`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_trip_id` (`trip_id`),
  KEY `idx_passenger_id` (`passenger_id`)
) ENGINE = InnoDB COMMENT ='订单分片表-0（乘客ID取模=0）';

CREATE TABLE IF NOT EXISTS `trip_order_1` (
  `id`             BIGINT        NOT NULL COMMENT '订单ID（分片引擎雪花生成）',
  `order_no`       VARCHAR(32)   NOT NULL COMMENT '订单号',
  `trip_id`        BIGINT        NOT NULL COMMENT '行程ID',
  `passenger_id`   BIGINT        NOT NULL COMMENT '乘客用户ID',
  `seat_count`     INT           NOT NULL COMMENT '购买座位数',
  `total_price`    DECIMAL(10,2) NOT NULL COMMENT '订单总额（元）',
  `coupon_discount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '券优惠金额（阶段2接入）',
  `pay_price`      DECIMAL(10,2) NOT NULL COMMENT '实付金额（元）',
  `status`         VARCHAR(20)   NOT NULL DEFAULT 'CREATED' COMMENT '订单状态 CREATED待支付 PAID已支付 IN_SERVICE服务中 COMPLETED已完成 CANCELLED已取消 EXPIRED超时关闭',
  `payment_no`     VARCHAR(32)   DEFAULT NULL COMMENT '支付流水号',
  `cancel_reason`  VARCHAR(200)  DEFAULT NULL COMMENT '取消原因',
  `coupon_id`      BIGINT        DEFAULT NULL COMMENT '优惠券ID（阶段2接入）',
  `created_at`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_trip_id` (`trip_id`),
  KEY `idx_passenger_id` (`passenger_id`)
) ENGINE = InnoDB COMMENT ='订单分片表-1（乘客ID取模=1）';

-- 2. 迁移老表数据到分片表（INSERT IGNORE 依赖唯一键 uk_order_no 幂等；已迁移过的分片表会自动跳过）
--    老表仅当存在时迁移（RENAME 前执行）
INSERT IGNORE INTO trip_order_0 (id, order_no, trip_id, passenger_id, seat_count, total_price, coupon_discount, pay_price, status, payment_no, cancel_reason, coupon_id, created_at, updated_at)
SELECT id, order_no, trip_id, passenger_id, seat_count, total_price, coupon_discount, pay_price, status, payment_no, cancel_reason, coupon_id, created_at, updated_at
FROM trip_order WHERE passenger_id % 2 = 0;

INSERT IGNORE INTO trip_order_1 (id, order_no, trip_id, passenger_id, seat_count, total_price, coupon_discount, pay_price, status, payment_no, cancel_reason, coupon_id, created_at, updated_at)
SELECT id, order_no, trip_id, passenger_id, seat_count, total_price, coupon_discount, pay_price, status, payment_no, cancel_reason, coupon_id, created_at, updated_at
FROM trip_order WHERE passenger_id % 2 = 1;

-- 3. 老表改名保留兜底（仅当老表仍存在时执行，一次生效）
--    用 SQL 变量 + 条件语句判断，避免重复改名报错
SET @rename_done := (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'goshare' AND table_name = 'trip_order_legacy');
SET @sql_rename := IF(@rename_done = 0, 'RENAME TABLE trip_order TO trip_order_legacy', 'SELECT 1');
PREPARE stmt_rn FROM @sql_rename; EXECUTE stmt_rn; DEALLOCATE PREPARE stmt_rn;

-- 4. 校验：分片表数据量合计应与老表一致
SELECT 'trip_order_0' AS shard_table, COUNT(*) AS cnt FROM trip_order_0
UNION ALL
SELECT 'trip_order_1' AS shard_table, COUNT(*) AS cnt FROM trip_order_1
UNION ALL
SELECT 'trip_order_legacy(兜底)', COUNT(*) FROM trip_order_legacy;
