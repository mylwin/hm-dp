-- 重投建单消息前，只读检查原订单是否仍有有效预占；不扣库存、不修改状态、不创建缺失键。
-- ARGV：用户、订单、券、代次；返回 RESERVED/CONFIRMED，否则返回 CONFLICT。
local stockKey, metaKey, usersKey, reservationKey = KEYS[1], KEYS[2], KEYS[3], KEYS[4]
-- 已受理请求应有完整库存、元数据、用户归属和预占；任何键缺失或类型错误都不能证明预占有效。
if redis.call('TYPE', stockKey).ok ~= 'string' or redis.call('TYPE', metaKey).ok ~= 'hash'
    or redis.call('TYPE', usersKey).ok ~= 'hash' or redis.call('TYPE', reservationKey).ok ~= 'hash' then return 'CONFLICT' end
-- 库存允许为 0：该订单可能已拿到最后一份；这里只核对格式，不代表再次购买。
local stockText = redis.call('GET', stockKey)
local stock = tonumber(stockText)
if not stock or not string.match(stockText, '^%d+$') or stock > 2147483647 then return 'CONFLICT' end
-- 同一用户、订单、券、库存版本和一份预占必须相互对应，用户当前归属也必须是该原订单。
if redis.call('HGET', metaKey, 'ready') ~= '1' or redis.call('HGET', metaKey, 'epoch') ~= ARGV[4]
    or redis.call('HGET', usersKey, ARGV[1]) ~= ARGV[2]
    or redis.call('HGET', reservationKey, 'userId') ~= ARGV[1]
    or redis.call('HGET', reservationKey, 'orderId') ~= ARGV[2]
    or redis.call('HGET', reservationKey, 'voucherId') ~= ARGV[3]
    or redis.call('HGET', reservationKey, 'epoch') ~= ARGV[4]
    or redis.call('HGET', reservationKey, 'quantity') ~= '1' then return 'CONFLICT' end
-- 只有 RESERVED 和 CONFIRMED 可继续核对原事件；CONFIRMED 是否真有数据库订单仍由 Java 检查。
local state = redis.call('HGET', reservationKey, 'state')
if state == 'RESERVED' or state == 'CONFIRMED' then return state end
return 'CONFLICT'
