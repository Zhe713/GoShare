-- ============================================================
-- GoShare 阶段1「换骨」：路线 / 站点 / 区间库存 / 履约生命周期
-- 目标：把"全程一个余座计数器"改成"区间覆盖计数"，把"付了钱就结束"改成完整履约闭环
-- 执行方式：docker exec -i goshare-mysql mysql -uroot -proot123 goshare < 本文件
-- 幂等：所有 ALTER 用 information_schema 判断；表用 CREATE TABLE IF NOT EXISTS
-- ============================================================

USE `goshare`;

-- ---------------------------------------------------------------------------
-- 1. trip 表改造：车辆快照 + 里程单价
-- ---------------------------------------------------------------------------
SET @c1 := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='goshare' AND TABLE_NAME='trip' AND COLUMN_NAME='price_per_km');
SET @s1 := IF(@c1=0, 'ALTER TABLE `trip` ADD COLUMN `price_per_km` DECIMAL(10,2) DEFAULT NULL COMMENT ''每公里单价（元），区间计价自变量'' AFTER `price`', 'SELECT 1');
PREPARE p1 FROM @s1; EXECUTE p1; DEALLOCATE PREPARE p1;

SET @c2 := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='goshare' AND TABLE_NAME='trip' AND COLUMN_NAME='total_distance_km');
SET @s2 := IF(@c2=0, 'ALTER TABLE `trip` ADD COLUMN `total_distance_km` DECIMAL(10,2) DEFAULT NULL COMMENT ''整段里程（公里），发布时按站点坐标计算'' AFTER `price_per_km`', 'SELECT 1');
PREPARE p2 FROM @s2; EXECUTE p2; DEALLOCATE PREPARE p2;

SET @c3 := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='goshare' AND TABLE_NAME='trip' AND COLUMN_NAME='vehicle_plate');
SET @s3 := IF(@c3=0, 'ALTER TABLE `trip` ADD COLUMN `vehicle_plate` VARCHAR(20) DEFAULT NULL COMMENT ''车牌号（发布时快照）'' AFTER `remark`', 'SELECT 1');
PREPARE p3 FROM @s3; EXECUTE p3; DEALLOCATE PREPARE p3;

SET @c4 := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='goshare' AND TABLE_NAME='trip' AND COLUMN_NAME='vehicle_model');
SET @s4 := IF(@c4=0, 'ALTER TABLE `trip` ADD COLUMN `vehicle_model` VARCHAR(50) DEFAULT NULL COMMENT ''车型（发布时快照）'' AFTER `vehicle_plate`', 'SELECT 1');
PREPARE p4 FROM @s4; EXECUTE p4; DEALLOCATE PREPARE p4;

SET @c5 := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='goshare' AND TABLE_NAME='trip' AND COLUMN_NAME='vehicle_color');
SET @s5 := IF(@c5=0, 'ALTER TABLE `trip` ADD COLUMN `vehicle_color` VARCHAR(20) DEFAULT NULL COMMENT ''车身颜色（发布时快照）'' AFTER `vehicle_model`', 'SELECT 1');
PREPARE p5 FROM @s5; EXECUTE p5; DEALLOCATE PREPARE p5;

SET @c6 := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='goshare' AND TABLE_NAME='trip' AND COLUMN_NAME='driver_nickname');
SET @s6 := IF(@c6=0, 'ALTER TABLE `trip` ADD COLUMN `driver_nickname` VARCHAR(50) DEFAULT NULL COMMENT ''车主昵称快照（下单前展示信任信息）'' AFTER `vehicle_color`', 'SELECT 1');
PREPARE p6 FROM @s6; EXECUTE p6; DEALLOCATE PREPARE p6;

-- ---------------------------------------------------------------------------
-- 2. 行程站点表 trip_stop
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `trip_stop` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '站点ID',
  `trip_id`       BIGINT        NOT NULL COMMENT '行程ID',
  `seq`           INT           NOT NULL COMMENT '站点序号（0=起点）',
  `city`          VARCHAR(50)   NOT NULL COMMENT '城市',
  `address`       VARCHAR(200)  DEFAULT NULL COMMENT '具体地址（上车/下车点）',
  `lng`           DECIMAL(10,6) DEFAULT NULL COMMENT '经度',
  `lat`           DECIMAL(10,6) DEFAULT NULL COMMENT '纬度',
  `offset_minutes` INT          NOT NULL DEFAULT 0 COMMENT '相对发车时间的预估到达分钟数',
  `created_at`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_trip_seq` (`trip_id`, `seq`)
) ENGINE = InnoDB COMMENT ='行程站点序列表（路线折线的顶点）';

-- ---------------------------------------------------------------------------
-- 3. 区间库存表 trip_segment
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `trip_segment` (
  `id`            BIGINT NOT NULL AUTO_INCREMENT COMMENT '区间ID',
  `trip_id`       BIGINT NOT NULL COMMENT '行程ID',
  `from_seq`      INT    NOT NULL COMMENT '区间起点站序号',
  `to_seq`        INT    NOT NULL COMMENT '区间终点站序号',
  `distance_km`   DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '区间里程（公里）',
  `remain_seats`  INT    NOT NULL COMMENT '区间剩余座位',
  `total_seats`   INT    NOT NULL COMMENT '区间总座位（=行程总座位）',
  `created_at`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_trip_seg` (`trip_id`, `from_seq`, `to_seq`)
) ENGINE = InnoDB COMMENT ='区间库存表（区间覆盖计数，替代全程标量余座）';

-- ---------------------------------------------------------------------------
-- 4. trip_order 改造：绑定上下车站点 + 区间里程
--    注意：trip_order 已分片成 trip_order_0/1（物理层无 trip_order 表），
--    逻辑表由 ShardingSphere 接管，故字段直接加到两个分片表上。
-- ---------------------------------------------------------------------------
SET @shard0 := (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='goshare' AND TABLE_NAME='trip_order_0');
SET @shard1 := (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='goshare' AND TABLE_NAME='trip_order_1');

SET @c10 := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='goshare' AND TABLE_NAME='trip_order_0' AND COLUMN_NAME='from_stop_seq');
SET @s10 := IF(@c10=0 AND @shard0>0, 'ALTER TABLE `trip_order_0` ADD COLUMN `from_stop_seq` INT DEFAULT NULL AFTER `seat_count`, ADD COLUMN `to_stop_seq` INT DEFAULT NULL AFTER `from_stop_seq`, ADD COLUMN `distance_km` DECIMAL(10,2) DEFAULT NULL AFTER `to_stop_seq`', 'SELECT 1');
PREPARE p10 FROM @s10; EXECUTE p10; DEALLOCATE PREPARE p10;

SET @c11 := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='goshare' AND TABLE_NAME='trip_order_1' AND COLUMN_NAME='from_stop_seq');
SET @s11 := IF(@c11=0 AND @shard1>0, 'ALTER TABLE `trip_order_1` ADD COLUMN `from_stop_seq` INT DEFAULT NULL AFTER `seat_count`, ADD COLUMN `to_stop_seq` INT DEFAULT NULL AFTER `from_stop_seq`, ADD COLUMN `distance_km` DECIMAL(10,2) DEFAULT NULL AFTER `to_stop_seq`', 'SELECT 1');
PREPARE p11 FROM @s11; EXECUTE p11; DEALLOCATE PREPARE p11;

-- ---------------------------------------------------------------------------
-- 5. 订单评价表 order_review
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `order_review` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '评价ID',
  `order_no`      VARCHAR(32)   NOT NULL COMMENT '订单号',
  `reviewer_id`   BIGINT        NOT NULL COMMENT '评价人（乘客）ID',
  `reviewee_id`   BIGINT        NOT NULL COMMENT '被评价人（车主）ID',
  `trip_id`       BIGINT        NOT NULL COMMENT '行程ID',
  `rating`        TINYINT       NOT NULL COMMENT '评分 1-5',
  `comment`       VARCHAR(500)  DEFAULT NULL COMMENT '评价内容',
  `created_at`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_reviewer` (`order_no`, `reviewer_id`),
  KEY `idx_reviewee` (`reviewee_id`)
) ENGINE = InnoDB COMMENT ='订单评价表（行程完成的履约闭环产物）';

-- ---------------------------------------------------------------------------
-- 6. 退款单表 refund_order + 退款流水 refund_transaction
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `refund_order` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '退款单ID',
  `refund_no`     VARCHAR(32)   NOT NULL COMMENT '退款单号',
  `order_no`      VARCHAR(32)   NOT NULL COMMENT '原订单号',
  `passenger_id`  BIGINT        NOT NULL COMMENT '乘客ID',
  `payment_no`    VARCHAR(32)   DEFAULT NULL COMMENT '原支付流水号',
  `refund_amount` DECIMAL(10,2) NOT NULL COMMENT '退款金额（元）',
  `fee_amount`    DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '扣除的违约金（元）',
  `reason`        VARCHAR(200)  DEFAULT NULL COMMENT '退款原因',
  `status`        VARCHAR(20)   NOT NULL DEFAULT 'APPLYING' COMMENT 'APPLYING申请中 APPROVED已审批 REFUNDING退款中 SUCCESS成功 FAILED失败',
  `created_at`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `refunded_at`   DATETIME      DEFAULT NULL COMMENT '退款成功时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refund_no` (`refund_no`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_passenger` (`passenger_id`)
) ENGINE = InnoDB COMMENT ='退款单表（已支付订单取消的退款闭环）';

CREATE TABLE IF NOT EXISTS `refund_transaction` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '退款流水ID',
  `refund_no`     VARCHAR(32)   NOT NULL COMMENT '退款单号',
  `order_no`      VARCHAR(32)   NOT NULL COMMENT '原订单号',
  `payment_no`    VARCHAR(32)   DEFAULT NULL COMMENT '原支付流水号',
  `channel`       VARCHAR(20)   NOT NULL DEFAULT 'MOCK' COMMENT '退款渠道',
  `amount`        DECIMAL(10,2) NOT NULL COMMENT '退款金额（元）',
  `status`        VARCHAR(20)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING处理中 SUCCESS成功 FAILED失败',
  `channel_ref_no` VARCHAR(64)  DEFAULT NULL COMMENT '渠道退款流水号',
  `created_at`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_refund_no` (`refund_no`),
  KEY `idx_order_no` (`order_no`)
) ENGINE = InnoDB COMMENT ='退款流水表（渠道退款记录）';

-- ---------------------------------------------------------------------------
-- 7. 站内通知表 notify_message
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `notify_message` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '通知ID',
  `user_id`       BIGINT        NOT NULL COMMENT '接收用户ID',
  `biz_type`      VARCHAR(30)   NOT NULL COMMENT '业务类型 ORDER_PAID订单支付 ORDER_CANCELLED订单取消 ORDER_EXPIRED订单超时 REFUND_SUCCESS退款成功 TRIP_FULL行程满座',
  `title`         VARCHAR(100)  NOT NULL COMMENT '标题',
  `content`       VARCHAR(500)  NOT NULL COMMENT '内容',
  `channel`       VARCHAR(20)   NOT NULL DEFAULT 'INBOX' COMMENT '渠道 INBOX站内信 SMS短信',
  `read_flag`     TINYINT       NOT NULL DEFAULT 0 COMMENT '是否已读 0否 1是',
  `biz_id`        VARCHAR(64)   DEFAULT NULL COMMENT '关联业务ID（订单号/退款号等）',
  `created_at`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_read` (`user_id`, `read_flag`),
  KEY `idx_biz_type` (`biz_type`)
) ENGINE = InnoDB COMMENT ='站内通知表（通知服务落点）';

-- ---------------------------------------------------------------------------
-- 8. 对账差异表 recon_diff
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `recon_diff` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '差异ID',
  `recon_date`    DATE          NOT NULL COMMENT '对账日期',
  `order_no`      VARCHAR(32)   NOT NULL COMMENT '订单号',
  `diff_type`     VARCHAR(30)   NOT NULL COMMENT '差异类型 PAYMENT_MISSING订单无流水 ORDER_PAID_WITHOUT_PAY订单已支付但无成功流水 AMOUNT_MISMATCH金额不一致',
  `detail`        VARCHAR(500)  DEFAULT NULL COMMENT '差异明细',
  `created_at`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_recon_date` (`recon_date`)
) ENGINE = InnoDB COMMENT ='对账差异表（T+1对账结果）';
