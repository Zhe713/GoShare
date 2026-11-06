-- ============================================================
-- GoShare E2E 种子数据（补充）：秒杀演示券模板
-- 背景：E2E 用例 fix-round2「券包新增可领秒杀券」依赖该模板；
--       环境重置/模板被清理后用例会因数据缺失失败（用例腐烂问题的数据源变体）。
-- 幂等：NOT EXISTS 防重，可重复执行。
-- 执行：docker exec -i goshare-mysql mysql -uroot -proot123 goshare < sql/06-e2e-seed.sql
-- ============================================================

USE `goshare`;

INSERT INTO coupon_template
  (template_name, type, threshold, discount_value, total_count, received_count, start_time, end_time, expire_days, status, remark)
SELECT '限时秒杀立减6元', 'CASH', 0.00, 6.00, 30, 0, NOW(), DATE_ADD(NOW(), INTERVAL 7 DAY), 7, 0, 'E2E秒杀演示券'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM coupon_template WHERE template_name = '限时秒杀立减6元');
