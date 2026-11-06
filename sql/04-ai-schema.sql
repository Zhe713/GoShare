-- ============================================
-- GoShare 顺风拼车平台 · 阶段 5：AI 出行助手数据库脚本
-- 库名：goshare
-- 内容：用户信任分（撮合排序特征）、LLM 调用记账、工单与工单消息（客服助手）
-- 幂等性：信任分用 information_schema 动态判断；表用 CREATE TABLE IF NOT EXISTS
-- 执行方式：docker exec goshare-mysql mysql -uroot -proot123 < 本文件
-- ============================================

SET NAMES utf8mb4;

USE `goshare`;

-- 1. 用户表补充：车主信任分（0-5，A1 撮合排序特征；MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，用 information_schema 动态判断）
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = 'goshare' AND TABLE_NAME = 'user' AND COLUMN_NAME = 'trust_score');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE `user` ADD COLUMN `trust_score` DECIMAL(3,1) NOT NULL DEFAULT 5.0 COMMENT ''车主信任分(0-5，撮合排序特征)''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. LLM 调用记账表（省 token 四关卡的效果度量：缓存命中/关卡拦截/模型名/耗时）
CREATE TABLE IF NOT EXISTS `llm_call_log` (
  `id`            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '记录ID',
  `biz_type`      VARCHAR(30) NOT NULL COMMENT '业务类型 A1语义搜索 / B1工单摘要 / A2取消挽留 / FAQ天气',
  `model_name`    VARCHAR(50) NOT NULL DEFAULT 'mock' COMMENT '实际使用的模型 mock/小模型/大模型',
  `prompt_hash`   VARCHAR(64) DEFAULT NULL COMMENT '固定前缀+问题 的哈希（答案缓存键）',
  `input_tokens`  INT         NOT NULL DEFAULT 0 COMMENT '输入 token 数（mock 按字数模拟）',
  `output_tokens` INT         NOT NULL DEFAULT 0 COMMENT '输出 token 数',
  `duration_ms`   INT         NOT NULL DEFAULT 0 COMMENT '耗时（毫秒）',
  `cache_hit`     TINYINT     NOT NULL DEFAULT 0 COMMENT '是否命中答案缓存 0否 1是',
  `gate_skipped`  TINYINT     NOT NULL DEFAULT 0 COMMENT '是否被固定前缀/小模型关卡拦截（未调大模型）0否 1是',
  `created_at`    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_biz_created` (`biz_type`, `created_at`)
) ENGINE = InnoDB COMMENT ='LLM 调用记账表';

-- 3. 工单表（B1 客服助手：会话摘要 + 回复草稿）
CREATE TABLE IF NOT EXISTS `work_order` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '工单ID',
  `order_no`      VARCHAR(32)  DEFAULT NULL COMMENT '关联订单号',
  `passenger_id`  BIGINT       DEFAULT NULL COMMENT '乘客用户ID',
  `category`      VARCHAR(30)  DEFAULT NULL COMMENT '问题分类（规则兜底打标：退款/改期/候补/其他）',
  `status`        VARCHAR(20)  NOT NULL DEFAULT 'OPEN' COMMENT '工单状态 OPEN待处理 PROCESSING处理中 RESOLVED已解决',
  `summary`       TEXT         DEFAULT NULL COMMENT 'AI 会话摘要',
  `reply_draft`   TEXT         DEFAULT NULL COMMENT 'AI 回复草稿',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_passenger` (`passenger_id`),
  KEY `idx_order_no` (`order_no`)
) ENGINE = InnoDB COMMENT ='工单表';

-- 4. 工单消息表（B1 会话记录）
CREATE TABLE IF NOT EXISTS `work_order_message` (
  `id`            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '消息ID',
  `work_order_id` BIGINT      NOT NULL COMMENT '工单ID',
  `sender`        VARCHAR(20) NOT NULL COMMENT '发送方 USER乘客 AGENT客服 ASSISTANT助手',
  `content`       TEXT        NOT NULL COMMENT '消息内容',
  `created_at`    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_work_order` (`work_order_id`)
) ENGINE = InnoDB COMMENT ='工单消息表';
