-- 确认成功订单的原预占，或归还已关闭请求暂扣的库存；数据库订单资格由 Java 调用方先核对。
-- KEYS：库存、元数据、用户归属、原订单预占；ARGV：用户、订单、券、代次、CONFIRM/RELEASE、允许缺失墓碑。
-- 返回 1 已完成（含重复），0 数据冲突。所有校验在第一次写入前执行。
local stockKey, metaKey, usersKey, reservationKey = KEYS[1], KEYS[2], KEYS[3], KEYS[4]
local userId, orderId, voucherId, epoch, action = ARGV[1], ARGV[2], ARGV[3], ARGV[4], ARGV[5]

-- 只接受确认和释放两种指令，未知操作返回 0，不进行库存写入。
if action ~= 'CONFIRM' and action ~= 'RELEASE' then return 0 end
-- 库存和元数据缺失或类型错误时无法安全操作，返回 0，交业务层保留任务核对。
if redis.call('TYPE', stockKey).ok ~= 'string' or redis.call('TYPE', metaKey).ok ~= 'hash' then return 0 end

local usersType, reservationType = redis.call('TYPE', usersKey).ok, redis.call('TYPE', reservationKey).ok
-- 用户归属和订单预占允许不存在；存在时必须为 Hash，避免后续读取错误或误改其他类型的键。
if (usersType ~= 'none' and usersType ~= 'hash') or (reservationType ~= 'none' and reservationType ~= 'hash') then return 0 end
-- 只处理 Redis 已准备好且库存版本相同的任务，旧代次不能确认或回补新代次库存。
if redis.call('HGET', metaKey, 'ready') ~= '1' or redis.call('HGET', metaKey, 'epoch') ~= epoch then return 0 end

local stockText = redis.call('GET', stockKey)
local stock = tonumber(stockText)
-- 库存须为 0 到 INT_MAX 的整数字符串；不能在坏数据上继续确认或做加一操作。
if not stock or not string.match(stockText, '^%d+$') or stock > 2147483647 then return 0 end

local owner = redis.call('HGET', usersKey, userId)
local state = redis.call('HGET', reservationKey, 'state')
-- 已有预占必须有状态，且用户、订单、券和代次逐项匹配；缺 state 的残缺 Hash 也属于冲突。
if reservationType == 'hash' and (not state
    or redis.call('HGET', reservationKey, 'userId') ~= userId
    or redis.call('HGET', reservationKey, 'orderId') ~= orderId
    or redis.call('HGET', reservationKey, 'voucherId') ~= voucherId
    or redis.call('HGET', reservationKey, 'epoch') ~= epoch) then return 0 end
-- 确认只把原预占标为 CONFIRMED，不再次扣库存；重复确认也要通过身份、数量及归属检查。
if action == 'CONFIRM' then
    -- 必须仍是一份 RESERVED/CONFIRMED 预占，且该用户的归属订单号仍是当前 orderId。
    if (state ~= 'RESERVED' and state ~= 'CONFIRMED') or owner ~= orderId
        or redis.call('HGET', reservationKey, 'quantity') ~= '1' then return 0 end
    -- 成功订单已经使用过这份预占，只更新状态，保留用户归属以防止再次购买。
    redis.call('HSET', reservationKey, 'state', 'CONFIRMED')
    return 1
end

-- 已释放不再动库存或用户归属；用户可能已经用新订单重新购买。
-- 同一订单已释放则直接确认完成，不再加库存，也不删除后来属于新订单的用户归属。
if state == 'RELEASED' then return 1 end
-- CONFIRMED 已对应成功订单，不能通过释放任务回补；返回 0 交业务层核对。
if state == 'CONFIRMED' then return 0 end
-- RESERVED 说明之前确实暂扣过一份；现在释放该份库存，首次才执行加一。
if state == 'RESERVED' then
    -- 核对用户归属的订单号、预占数量和加一上限，防止归还其他订单的库存或数值溢出。
    if owner ~= orderId or redis.call('HGET', reservationKey, 'quantity') ~= '1' or stock >= 2147483647 then return 0 end
    -- 归还本订单之前暂扣的一份库存，再移除已核对属于该订单的用户购买资格。
    redis.call('INCR', stockKey)
    redis.call('HDEL', usersKey, userId)
-- REJECTED 没有成功暂扣库存；通过检查后只写释放终态，不进入上面的加一分支。
elseif state == 'REJECTED' then
    -- 被拒绝请求的数量应为 0，用户归属不应是该订单；不删除其他订单已有的资格。
    if owner == orderId or redis.call('HGET', reservationKey, 'quantity') ~= '0' then return 0 end
elseif not state then
    -- 仅原 PREPARING 请求被显式关闭时允许缺记录；写 RELEASED 墓碑阻止迟到预占，不凭空加库存。
    if ARGV[6] ~= '1' or owner == orderId then return 0 end
else
    return 0
end
-- 保留 RELEASED、原身份及 quantity=0 作为终态记录，后续重复释放或迟到预占都能识别原结果。
redis.call('HSET', reservationKey, 'state', 'RELEASED', 'userId', userId, 'orderId', orderId,
    'voucherId', voucherId, 'epoch', epoch, 'quantity', '0')
return 1
