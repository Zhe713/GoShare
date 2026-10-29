-- ============================================
-- GoShare 顺风拼车平台 · 数据库脚本（阶段 2：优惠券）
-- 执行方式：docker exec 手动执行，或 docker compose 首次启动自动执行
-- ============================================

USE `goshare`;

-- 1. 优惠券模板表
CREATE TABLE IF NOT EXISTS `coupon_template` (
  `id`             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '模板ID',
  `template_name`  VARCHAR(50)   NOT NULL COMMENT '券名称（如：满50减10）',
  `type`           VARCHAR(20)   NOT NULL COMMENT '券类型 FULL_REDUCTION满减 DISCOUNT折扣 CASH立减',
  `threshold`      DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '满X元可用（0=无门槛）',
  `discount_value` DECIMAL(10,2) NOT NULL COMMENT '满减/立减金额（元），折扣券为折扣率（如85=8.5折）',
  `total_count`    INT           NOT NULL COMMENT '发行总量（0=不限量）',
  `received_count` INT           NOT NULL DEFAULT 0 COMMENT '已领取数量',
  `start_time`     DATETIME      NOT NULL COMMENT '领取开始时间',
  `end_time`       DATETIME      NOT NULL COMMENT '领取结束时间',
  `expire_days`    INT           NOT NULL DEFAULT 7 COMMENT '领取后有效期（天）',
  `status`         TINYINT       NOT NULL DEFAULT 0 COMMENT '状态 0上架 1下架',
  `remark`         VARCHAR(200)  DEFAULT NULL COMMENT '备注',
  `created_at`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_status_time` (`status`, `start_time`, `end_time`)
) ENGINE = InnoDB COMMENT ='优惠券模板表';

-- 2. 用户手里的券表
CREATE TABLE IF NOT EXISTS `user_coupon` (
  `id`               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '券实例ID',
  `coupon_no`        VARCHAR(32)   NOT NULL COMMENT '券号（防重复）',
  `coupon_template_id` BIGINT      NOT NULL COMMENT '模板ID',
  `user_id`          BIGINT        NOT NULL COMMENT '所属用户ID',
  `type`             VARCHAR(20)   NOT NULL COMMENT '券类型（冗余模板快照）',
  `threshold`        DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '满X元可用（冗余快照）',
  `discount_value`   DECIMAL(10,2) NOT NULL COMMENT '折扣金额/率（冗余快照）',
  `status`           TINYINT       NOT NULL DEFAULT 0 COMMENT '状态 0未用 1已用 2已过期 3锁定中（下单占住，支付确认/取消释放）',
  `order_no`         VARCHAR(32)   DEFAULT NULL COMMENT '用在哪张订单',
  `received_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  `used_at`          DATETIME      DEFAULT NULL COMMENT '使用时间',
  `expire_at`        DATETIME      NOT NULL COMMENT '可用截止时间',
  `created_at`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_coupon_no` (`coupon_no`),
  UNIQUE KEY `uk_template_user` (`coupon_template_id`, `user_id`),
  KEY `idx_user_status` (`user_id`, `status`)
) ENGINE = InnoDB COMMENT ='用户手里的券表';

-- 3. 券变动流水表（审计）
CREATE TABLE IF NOT EXISTS `coupon_log` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '流水ID',
  `user_id`    BIGINT       NOT NULL COMMENT '用户ID',
  `coupon_id`  BIGINT       NOT NULL COMMENT '券实例ID',
  `action`     VARCHAR(20)  NOT NULL COMMENT '动作 RECEIVE领取 LOCK锁定 USE用券 RETURN退券 EXPIRE过期',
  `order_no`   VARCHAR(32)  DEFAULT NULL COMMENT '关联订单号',
  `amount`     DECIMAL(10,2) DEFAULT 0.00 COMMENT '涉及金额（元）',
  `remark`     VARCHAR(200) DEFAULT NULL COMMENT '备注',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_coupon_id` (`coupon_id`)
) ENGINE = InnoDB COMMENT ='券变动流水表';

-- 4. 订单表补充券字段（下单用券抵扣）
-- 注意：MySQL 8 不支持 ADD COLUMN IF NOT EXISTS（MariaDB 才支持），此处用 information_schema 判断后动态执行
SET @exist := (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = 'goshare' AND table_name = 'trip_order' AND column_name = 'coupon_id');
SET @sql := IF(@exist = 0,
               'ALTER TABLE trip_order ADD COLUMN coupon_id BIGINT DEFAULT NULL COMMENT ''使用的券ID（阶段2接入）'' AFTER coupon_discount',
               'SELECT ''coupon_id 列已存在，跳过'' AS tip');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
