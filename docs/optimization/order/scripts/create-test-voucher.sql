-- 在 hm-dp 开发库的同一个 SQL 控制台中完整执行；语句失败时停止并 ROLLBACK。
-- 前提：已执行第二、三阶段迁移，店铺 1 存在。本脚本不创建订单、不重置已有库存。
-- 每次执行使用数据库自增编号创建新券，实际编号以最后的查询结果为准。
SET NAMES utf8mb4;
SET SESSION time_zone = '+00:00';
SET @test_shop_id = 1;
SET @test_stock = 10;
SET @test_epoch = 1;
SET @test_begin = NOW() - INTERVAL 1 DAY;
SET @test_end = NOW() + INTERVAL 7 DAY;

START TRANSACTION;

-- 基本信息：支付 50 元、抵扣 100 元，金额单位为分。
INSERT INTO tb_voucher
    (shop_id, title, sub_title, rules, pay_value, actual_value, type, status)
SELECT id, 'RocketMQ 下单联调券', '50 元抢 100 元代金券',
       '仅用于开发环境建单测试；每人限购一张', 5000, 10000, 1, 1
FROM tb_shop WHERE id = @test_shop_id;

-- 店铺不存在时置空，使下面的非空约束阻止继续创建不完整活动。
SET @test_voucher_id = (SELECT LAST_INSERT_ID() FROM tb_shop WHERE id = @test_shop_id);

INSERT INTO tb_seckill_voucher (voucher_id, stock, begin_time, end_time)
VALUES (@test_voucher_id, @test_stock, @test_begin, @test_end);

-- 先暂停；执行 Redis 初始化命令后，再执行结果集中的 enable_campaign_sql。
INSERT INTO tb_seckill_campaign_control (voucher_id, epoch, status)
VALUES (@test_voucher_id, @test_epoch, 'PAUSED');

COMMIT;

-- 结果集 1：记住实际 voucher_id，购买 URL 使用这个编号。
SELECT v.id AS voucher_id, v.shop_id, v.title, v.type, v.status AS voucher_status,
       s.stock, s.begin_time AS begin_time_utc, s.end_time AS end_time_utc,
       c.epoch, c.status AS campaign_status,
       (SELECT COUNT(*) FROM tb_voucher_order o WHERE o.voucher_id = v.id) AS existing_orders
FROM tb_voucher v
JOIN tb_seckill_voucher s ON s.voucher_id = v.id
JOIN tb_seckill_campaign_control c ON c.voucher_id = v.id
WHERE v.id = @test_voucher_id;

-- 结果集 2：复制整条 redis_init_command 到 Redis 控制台执行，应返回 OK。
-- Lua 只初始化全新键；重复执行会报 already_initialized，避免覆盖已扣减的库存。
-- beginAt/endAt 使用上面数据库时间转换出的 Unix 毫秒，与 Redis TIME 比较。
SELECT CONCAT(
    'EVAL "if redis.call(''EXISTS'',KEYS[1],KEYS[2],KEYS[3]) ~= 0 then ',
    'return redis.error_reply(''already_initialized'') end ',
    'redis.call(''SET'',KEYS[1],ARGV[1]); ',
    'redis.call(''HSET'',KEYS[2],''ready'',''1'',''epoch'',ARGV[2],',
    '''beginAt'',ARGV[3],''endAt'',ARGV[4]); return ''OK''" 3 ',
    'hmdp:seckill:{', @test_voucher_id, '}:stock ',
    'hmdp:seckill:{', @test_voucher_id, '}:meta ',
    'hmdp:seckill:{', @test_voucher_id, '}:users ',
    @test_stock, ' ', @test_epoch, ' ',
    CAST(UNIX_TIMESTAMP(@test_begin) * 1000 AS UNSIGNED), ' ',
    CAST(UNIX_TIMESTAMP(@test_end) * 1000 AS UNSIGNED)
) AS redis_init_command;

-- 结果集 3：仅在 Redis 返回 OK、existing_orders=0 后复制执行；首次应更新 1 行。
-- 这里的条件也会阻止把已有历史订单的孤立券编号作为全新活动开放。
SELECT CONCAT(
    'UPDATE tb_seckill_campaign_control SET status = ''READY'', updated_at = UTC_TIMESTAMP(3) ',
    'WHERE voucher_id = ', @test_voucher_id, ' AND epoch = ', @test_epoch,
    ' AND status = ''PAUSED'' AND NOT EXISTS ',
    '(SELECT 1 FROM tb_voucher_order WHERE voucher_id = ', @test_voucher_id, ');'
) AS enable_campaign_sql;
