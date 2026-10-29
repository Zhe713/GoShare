-- ============================================
-- GoShare 顺风拼车平台 · 数据库初始化脚本（阶段 1：核心链路）
-- 库名：goshare（由 docker-compose 创建）
-- 执行方式：docker compose 首次启动自动执行，或手动 docker exec 执行
-- ============================================

USE `goshare`;

-- 1. 用户表
CREATE TABLE IF NOT EXISTS `user` (
  `id`                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `phone`              VARCHAR(20)  NOT NULL COMMENT '手机号',
  `password_hash`      VARCHAR(100) NOT NULL COMMENT '密码哈希（BCrypt）',
  `nickname`           VARCHAR(50)  DEFAULT NULL COMMENT '昵称',
  `avatar_url`         VARCHAR(255) DEFAULT NULL COMMENT '头像地址',
  `real_name`          VARCHAR(50)  DEFAULT NULL COMMENT '真实姓名',
  `id_card_no`         VARCHAR(30)  DEFAULT NULL COMMENT '身份证号（演示项目明文存储）',
  `real_name_verified` TINYINT      NOT NULL DEFAULT 0 COMMENT '是否实名认证 0否 1是',
  `driver_verified`    TINYINT      NOT NULL DEFAULT 0 COMMENT '是否车主认证 0否 1是',
  `role`               VARCHAR(20)  NOT NULL DEFAULT 'PASSENGER' COMMENT '角色 PASSENGER乘客 DRIVER车主 ADMIN管理员',
  `status`             TINYINT      NOT NULL DEFAULT 1 COMMENT '状态 1正常 0禁用',
  `created_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_phone` (`phone`)
) ENGINE = InnoDB COMMENT ='用户表';

-- 2. 车主认证表
CREATE TABLE IF NOT EXISTS `driver_certification` (
  `id`                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '认证记录ID',
  `user_id`            BIGINT       NOT NULL COMMENT '用户ID',
  `license_plate`      VARCHAR(20)  NOT NULL COMMENT '车牌号',
  `vehicle_model`      VARCHAR(50)  DEFAULT NULL COMMENT '车型',
  `seat_count`         INT          NOT NULL DEFAULT 4 COMMENT '可拼座位数',
  `driving_license_no` VARCHAR(50)  DEFAULT NULL COMMENT '驾驶证号',
  `status`             TINYINT      NOT NULL DEFAULT 0 COMMENT '审核状态 0待审核 1通过 2拒绝',
  `audit_remark`       VARCHAR(200) DEFAULT NULL COMMENT '审核备注',
  `created_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_id` (`user_id`)
) ENGINE = InnoDB COMMENT ='车主认证表';

-- 3. 行程表
CREATE TABLE IF NOT EXISTS `trip` (
  `id`             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '行程ID',
  `driver_id`      BIGINT        NOT NULL COMMENT '车主用户ID',
  `from_city`      VARCHAR(50)   NOT NULL COMMENT '出发城市',
  `to_city`        VARCHAR(50)   NOT NULL COMMENT '到达城市',
  `departure_time` DATETIME      NOT NULL COMMENT '出发时间',
  `total_seats`    INT           NOT NULL COMMENT '总座位数',
  `remain_seats`   INT           NOT NULL COMMENT '剩余座位数',
  `price`          DECIMAL(10,2) NOT NULL COMMENT '每人价格（元）',
  `status`         TINYINT       NOT NULL DEFAULT 0 COMMENT '行程状态 0待出发 1进行中 2已完成 3已取消',
  `remark`         VARCHAR(200)  DEFAULT NULL COMMENT '备注（可带大件等）',
  `created_at`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_route_time` (`from_city`, `to_city`, `departure_time`)
) ENGINE = InnoDB COMMENT ='行程表';

-- 4. 订单表
CREATE TABLE IF NOT EXISTS `trip_order` (
  `id`             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '订单ID',
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
  `created_at`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_trip_id` (`trip_id`),
  KEY `idx_passenger_id` (`passenger_id`)
) ENGINE = InnoDB COMMENT ='订单表';

-- 5. 支付表
CREATE TABLE IF NOT EXISTS `payment` (
  `id`          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '支付记录ID',
  `payment_no`  VARCHAR(32)   NOT NULL COMMENT '支付流水号',
  `order_no`    VARCHAR(32)   NOT NULL COMMENT '订单号',
  `passenger_id` BIGINT       NOT NULL COMMENT '乘客用户ID',
  `amount`      DECIMAL(10,2) NOT NULL COMMENT '支付金额（元）',
  `status`      VARCHAR(20)   NOT NULL DEFAULT 'PENDING' COMMENT '支付状态 PENDING待支付 SUCCESS成功 FAILED失败 REFUNDED已退款',
  `channel`     VARCHAR(20)   NOT NULL DEFAULT 'MOCK' COMMENT '支付渠道（演示用模拟）',
  `created_at`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `paid_at`     DATETIME      DEFAULT NULL COMMENT '支付成功时间',
  `updated_at`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_payment_no` (`payment_no`),
  KEY `idx_order_no` (`order_no`)
) ENGINE = InnoDB COMMENT ='支付表';

-- 6. 幂等记录表（防重复下单/重复回调）
CREATE TABLE IF NOT EXISTS `idempotency_key` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '记录ID',
  `idempotent_key` VARCHAR(64) NOT NULL COMMENT '幂等键（业务类型+业务号）',
  `biz_type`      VARCHAR(30)  NOT NULL COMMENT '业务类型 CREATE_ORDER / PAY_CALLBACK',
  `response_json` TEXT         DEFAULT NULL COMMENT '首次处理的响应结果（重复请求直接返回）',
  `expire_at`     DATETIME     NOT NULL COMMENT '过期时间',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_key` (`idempotent_key`),
  KEY `idx_expire_at` (`expire_at`)
) ENGINE = InnoDB COMMENT ='幂等记录表';
