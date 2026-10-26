-- 在项目根目录用 MySQL 客户端对目标库手动执行；先执行第二、三阶段迁移。
-- 历史控制行默认不接管，禁止把历史暂停活动当成全新库存初始化。
SET @hmdp_campaign_publication_ddl = IF(EXISTS(
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'tb_seckill_campaign_control'
        AND column_name = 'publication_status'
), 'SELECT 1', 'ALTER TABLE tb_seckill_campaign_control ADD COLUMN publication_status VARCHAR(16) NOT NULL DEFAULT ''UNMANAGED'' COMMENT ''UNMANAGED / PENDING / PUBLISHED''');
PREPARE hmdp_campaign_publication_statement FROM @hmdp_campaign_publication_ddl;
EXECUTE hmdp_campaign_publication_statement;
DEALLOCATE PREPARE hmdp_campaign_publication_statement;
-- 可重复执行，不修改已有发布状态；DDL 会隐式提交，不能靠外层事务回滚。
