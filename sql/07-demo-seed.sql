-- ============================================================
-- GoShare 演示账号种子（E2E 与 README 快速开始依赖）
--
-- 为什么需要：E2E 与文档都用固定的演示手机号登录，之前这些账号是本地手工建的、
-- 没有进初始化脚本，导致全新环境（CI / 新同学 clone）里 E2E 直接登录失败。
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

USE `goshare`;

INSERT INTO `user` (`phone`, `password_hash`, `nickname`, `role`, `real_name_verified`, `driver_verified`, `status`)
VALUES
  ('13800138000', '$2a$10$sy4iONEQGDHTPADKS.vTt.ujMXffYAa5Hdiotec0H/p6/7/GXgbFO', '张车主', 'DRIVER', 1, 1, 1),
  ('13900139000', '$2a$10$ccNXcawfRV7kix0DCb2HRugVXqczAGjy6/wEo1zKlUdoNRujGZQNW', '李师傅', 'DRIVER', 0, 1, 1),
  ('13700137000', '$2a$10$GMIoHJfC41CpX416C.woNeM8Ui3tUVSzEfX8u41uqmKc.1ZGukHDS', '王乘客', 'PASSENGER', 0, 0, 1),
  ('13600136000', '$2a$10$Xte.hD5d2ZvdmPYz5zzE/uMFt.qrX1o8X.R8ZuhyNybd1NNqMfTga', '赵乘客', 'PASSENGER', 0, 0, 1)
ON DUPLICATE KEY UPDATE
  `role` = VALUES(`role`),
  `driver_verified` = VALUES(`driver_verified`),
  `real_name_verified` = VALUES(`real_name_verified`),
  `status` = VALUES(`status`);
