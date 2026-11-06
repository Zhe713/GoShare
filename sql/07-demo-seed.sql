-- ============================================================
-- GoShare 演示账号种子（E2E 与 README 快速开始依赖）
--
-- 为什么需要：E2E 与文档都用固定的演示手机号登录，之前这些账号是本地手工建的、
-- 没有进初始化脚本，导致全新环境（CI / 新同学 clone）里 E2E 第一步登录就失败。
--
-- 密码统一 123456（BCrypt）。幂等：按手机号唯一索引做 INSERT ... ON DUPLICATE KEY UPDATE，
-- 重复执行只更新角色与认证状态，不会产生重复账号。
--
-- 角色说明：
--   13800138000  DRIVER    E2E 司机账号（发布行程）
--   13900139000  DRIVER    E2E 乘客侧登录 & 数据自愈时也用它发车
--   13700137000  PASSENGER 普通乘客（演示用）
--   13600136000  PASSENGER 普通乘客（演示用）
-- ============================================================

SET NAMES utf8mb4;

USE `goshare`;

-- ---------------- 演示账号 ----------------
-- 13800138000  车主  E2E 司机账号（发布行程）
-- 13900139000  车主  E2E 乘客侧登录 & 数据自愈时也用它发车
-- 13700137000  乘客  普通乘客（演示用）
-- 13600136000  乘客  普通乘客（演示用）
-- 逐条 NOT EXISTS 插入：不用 ON DUPLICATE KEY UPDATE 的 VALUES()（MySQL 8.0.20 起废弃，8.4 会报错）
INSERT INTO `user` (`phone`, `password_hash`, `nickname`, `role`, `real_name_verified`, `driver_verified`, `status`)
SELECT '13800138000', '$2a$10$sy4iONEQGDHTPADKS.vTt.ujMXffYAa5Hdiotec0H/p6/7/GXgbFO', '张车主', 'DRIVER', 1, 1, 1
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `user` WHERE `phone` = '13800138000');

INSERT INTO `user` (`phone`, `password_hash`, `nickname`, `role`, `real_name_verified`, `driver_verified`, `status`)
SELECT '13900139000', '$2a$10$ccNXcawfRV7kix0DCb2HRugVXqczAGjy6/wEo1zKlUdoNRujGZQNW', '李师傅', 'DRIVER', 0, 1, 1
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `user` WHERE `phone` = '13900139000');

INSERT INTO `user` (`phone`, `password_hash`, `nickname`, `role`, `real_name_verified`, `driver_verified`, `status`)
SELECT '13700137000', '$2a$10$GMIoHJfC41CpX416C.woNeM8Ui3tUVSzEfX8u41uqmKc.1ZGukHDS', '王乘客', 'PASSENGER', 0, 0, 1
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `user` WHERE `phone` = '13700137000');

INSERT INTO `user` (`phone`, `password_hash`, `nickname`, `role`, `real_name_verified`, `driver_verified`, `status`)
SELECT '13600136000', '$2a$10$Xte.hD5d2ZvdmPYz5zzE/uMFt.qrX1o8X.R8ZuhyNybd1NNqMfTga', '赵乘客', 'PASSENGER', 0, 0, 1
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `user` WHERE `phone` = '13600136000');

-- ------------------------------------------------------------
-- 券模板种子（E2E 券包用例 + 演示需要）
-- 用 NOW() 生成有效期，保证全新环境里券永远是"可领且在有效期内"；
-- 按 template_name 幂等（同名只保留一条）。
-- ------------------------------------------------------------
INSERT INTO `coupon_template`
  (`template_name`, `type`, `threshold`, `discount_value`, `total_count`, `received_count`,
   `start_time`, `end_time`, `expire_days`, `status`, `remark`)
SELECT '新客满50减10', 'FULL_REDUCTION', 50.00, 10.00, 1000, 0,
       NOW(), DATE_ADD(NOW(), INTERVAL 30 DAY), 30, 0, '演示券模板'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `coupon_template` WHERE `template_name` = '新客满50减10');

INSERT INTO `coupon_template`
  (`template_name`, `type`, `threshold`, `discount_value`, `total_count`, `received_count`,
   `start_time`, `end_time`, `expire_days`, `status`, `remark`)
SELECT '拼车8.5折', 'DISCOUNT', 0.00, 0.85, 1000, 0,
       NOW(), DATE_ADD(NOW(), INTERVAL 30 DAY), 30, 0, '演示券模板'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `coupon_template` WHERE `template_name` = '拼车8.5折');

INSERT INTO `coupon_template`
  (`template_name`, `type`, `threshold`, `discount_value`, `total_count`, `received_count`,
   `start_time`, `end_time`, `expire_days`, `status`, `remark`)
SELECT '周末满80减15', 'FULL_REDUCTION', 80.00, 15.00, 500, 0,
       NOW(), DATE_ADD(NOW(), INTERVAL 30 DAY), 30, 0, '演示券模板'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `coupon_template` WHERE `template_name` = '周末满80减15');

-- ------------------------------------------------------------
-- E2E 历史数据：乘客 13900139000 的 4 笔已支付订单 + 1 张已领券
-- 「我的页最近订单 >3 张卡」「领券中心已领券置灰」两条用例依赖，
-- 之前这些数据只存在于本地开发库，全新环境必挂。
-- ------------------------------------------------------------
SET @driver_id := (SELECT id FROM `user` WHERE phone = '13800138000' LIMIT 1);
SET @passenger_id := (SELECT id FROM `user` WHERE phone = '13900139000' LIMIT 1);

-- 一笔"已结束"的历史行程（订单展示用，时间在过去）
-- 这些表的 id 由应用/雪花生成（无 AUTO_INCREMENT），种子必须显式给 id
INSERT INTO `trip` (`id`, `driver_id`, `from_city`, `to_city`, `departure_time`, `total_seats`, `remain_seats`,
                    `price`, `status`, `remark`)
SELECT 9900001, @driver_id, '石家庄', '邯郸', DATE_SUB(NOW(), INTERVAL 3 DAY), 4, 0, 40.00, 3, 'E2E 历史订单行程'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `trip` WHERE `id` = 9900001);

SET @hist_trip := 9900001;

-- 4 笔已支付订单（passenger_id=2 → 偶数 → 分片表 trip_order_0）
INSERT INTO `trip_order_0` (`id`, `order_no`, `trip_id`, `passenger_id`, `seat_count`, `total_price`,
                            `coupon_discount`, `pay_price`, `status`, `payment_no`)
SELECT 9900101, '20261001000000000001', @hist_trip, @passenger_id, 1, 40.00, 0.00, 40.00, 'PAID', 'P20261001000000000001'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `trip_order_0` WHERE `order_no` = '20261001000000000001');

INSERT INTO `trip_order_0` (`id`, `order_no`, `trip_id`, `passenger_id`, `seat_count`, `total_price`,
                            `coupon_discount`, `pay_price`, `status`, `payment_no`)
SELECT 9900102, '20261002000000000002', @hist_trip, @passenger_id, 1, 40.00, 0.00, 40.00, 'PAID', 'P20261002000000000002'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `trip_order_0` WHERE `order_no` = '20261002000000000002');

INSERT INTO `trip_order_0` (`id`, `order_no`, `trip_id`, `passenger_id`, `seat_count`, `total_price`,
                            `coupon_discount`, `pay_price`, `status`, `payment_no`)
SELECT 9900103, '20261003000000000003', @hist_trip, @passenger_id, 1, 40.00, 0.00, 40.00, 'PAID', 'P20261003000000000003'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `trip_order_0` WHERE `order_no` = '20261003000000000003');

INSERT INTO `trip_order_0` (`id`, `order_no`, `trip_id`, `passenger_id`, `seat_count`, `total_price`,
                            `coupon_discount`, `pay_price`, `status`, `payment_no`)
SELECT 9900104, '20261004000000000004', @hist_trip, @passenger_id, 1, 40.00, 0.00, 40.00, 'PAID', 'P20261004000000000004'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `trip_order_0` WHERE `order_no` = '20261004000000000004');

-- 1 张已领取的券（「已领取，每人限领一张」置灰用）
SET @tpl := (SELECT id FROM `coupon_template` WHERE `template_name` = '新客满50减10' LIMIT 1);

INSERT INTO `user_coupon` (`id`, `coupon_no`, `coupon_template_id`, `user_id`, `type`, `threshold`,
                           `discount_value`, `status`, `expire_at`)
SELECT 9900201, 'C20261001000000000001', @tpl, @passenger_id, 'FULL_REDUCTION', 50.00, 10.00, 0,
       DATE_ADD(NOW(), INTERVAL 30 DAY)
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `user_coupon` WHERE `coupon_no` = 'C20261001000000000001');
