-- 在项目根目录使用 MySQL 客户端手动执行；先停止旧、新下单写入并备份。
-- 先查看重复订单，禁止自动删除历史数据。有重复时下面 ADD UNIQUE 会失败并保留原数据。
SELECT user_id, voucher_id, COUNT(*) AS duplicate_count
FROM tb_voucher_order GROUP BY user_id, voucher_id HAVING COUNT(*) > 1;

-- 完整的双列唯一索引已存在时不重复添加，支持重复执行。
SET @hmdp_order_unique_ddl = IF(EXISTS(
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'tb_voucher_order' AND non_unique = 0
    GROUP BY index_name
    HAVING COUNT(*) = 2 AND SUM(column_name = 'user_id') = 1
        AND SUM(column_name = 'voucher_id') = 1 AND SUM(sub_part IS NOT NULL) = 0
), 'SELECT 1', 'ALTER TABLE tb_voucher_order ADD UNIQUE KEY uk_order_user_voucher(user_id, voucher_id)');
PREPARE hmdp_order_unique_statement FROM @hmdp_order_unique_ddl;
EXECUTE hmdp_order_unique_statement;
DEALLOCATE PREPARE hmdp_order_unique_statement;
-- MySQL DDL 会隐式提交，不能依赖外层事务回滚；本脚本不自动应用到业务库。
