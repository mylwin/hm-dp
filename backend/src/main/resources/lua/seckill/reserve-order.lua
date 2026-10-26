-- KEYS：可用库存、元数据、用户归属 Hash、按订单预占 Hash。全部使用同一券的 hash tag。
-- ARGV：userId、orderId、voucherId、epoch。编号以字符串比较，避免 Lua 大整数精度丢失。
-- 返回：0 已预占，1 无库存，2 已购买/预占，3 未就绪，4 未开始，5 已结束，6 终态，7 标识冲突。
local stockKey, metaKey, usersKey, reservationKey = KEYS[1], KEYS[2], KEYS[3], KEYS[4]
local userId, orderId, voucherId, epoch = ARGV[1], ARGV[2], ARGV[3], ARGV[4]

-- Lua 出错不会回滚已执行写入，故所有类型和数值校验均放在第一次写入之前。
-- 如果库存或元数据不存在，则返回 3（未就绪）。
if redis.call('TYPE', stockKey).ok ~= 'string' or redis.call('TYPE', metaKey).ok ~= 'hash' then
    return 3
end

local usersType = redis.call('TYPE', usersKey).ok
local reservationType = redis.call('TYPE', reservationKey).ok
-- 如果用户归属不为哈希或不存在 或者 预占不为哈希或不存在，则返回 3（未就绪）。
if (usersType ~= 'none' and usersType ~= 'hash') or (reservationType ~= 'none' and reservationType ~= 'hash') then
    return 3
end

local state = redis.call('HGET', reservationKey, 'state')
-- 如果预占状态存在，则进行状态检查。
if state then
    -- 如果用户或券号不匹配，则返回 7（标识冲突）。
    if redis.call('HGET', reservationKey, 'userId') ~= userId
        or redis.call('HGET', reservationKey, 'voucherId') ~= voucherId then
        return 7
    end
    -- 如果预占状态为拒绝
    if state == 'REJECTED' then
        -- 如果拒绝原因在 1~5 之间，则返回拒绝原因。否则返回 6（终态）。
        local reason = tonumber(redis.call('HGET', reservationKey, 'reasonCode'))
        if reason == 1 or reason == 2 or reason == 4 or reason == 5 then return reason end
        return 6
    end
    -- 如果预占状态为释放或确认，则返回 6（终态）。
    if state == 'RELEASED' or state == 'CONFIRMED' then return 6 end
    -- 如果预占状态既非释放也非确认且非预占，则返回 3（未就绪）。
    if state ~= 'RESERVED' then return 3 end
end

-- 如果元数据未就绪或元数据 epoch 不匹配，则返回 3（未就绪）。
if redis.call('HGET', metaKey, 'ready') ~= '1' or redis.call('HGET', metaKey, 'epoch') ~= epoch then
    return 3
end

local stockText = redis.call('GET', stockKey)
local stock = tonumber(stockText)
local beginAt = tonumber(redis.call('HGET', metaKey, 'beginAt'))
local endAt = tonumber(redis.call('HGET', metaKey, 'endAt'))
-- 如果库存不存在 或者 库存数量超出范围 或者 开始时间晚于结束时间，则返回 3（未就绪）。
if not stock or not string.match(stockText, '^%d+$') or stock > 2147483647
    or not beginAt or not endAt or beginAt >= endAt then return 3 end

-- 获取用户归属
local owner = redis.call('HGET', usersKey, userId)
-- 如果预占状态为预占，则进行预占检查。
if state == 'RESERVED' then
    -- 如果预占 epoch 不匹配 或者 预占订单号不匹配，则返回 3（未就绪）。
    if redis.call('HGET', reservationKey, 'epoch') ~= epoch or owner ~= orderId then return 3 end
    return 0
end

local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
-- 已知拒绝也写终态，阻止先前超时、随后才到达的同订单预占命令重新扣减。
local function reject(code)
    redis.call('HSET', reservationKey, 'state', 'REJECTED', 'userId', userId, 'voucherId', voucherId,
        'orderId', orderId, 'epoch', epoch, 'quantity', '0', 'reasonCode', tostring(code))
    return code
end

-- 如果当前时间早于开始时间，则返回 4（未开始）。
if now < beginAt then return reject(4) end
-- 如果当前时间晚于结束时间，则返回 5（已结束）。
if now >= endAt then return reject(5) end
-- 如果用户已拥有订单，则返回 2（已购买/预占）。
if owner then return reject(2) end
-- 如果库存为 0，则返回 1（无库存）。
if stock < 1 then return reject(1) end

-- 扣减库存
redis.call('DECR', stockKey)
-- 记录用户归属
redis.call('HSET', usersKey, userId, orderId)
-- 记录预占状态
redis.call('HSET', reservationKey, 'state', 'RESERVED', 'userId', userId, 'voucherId', voucherId,
    'orderId', orderId, 'epoch', epoch, 'quantity', '1')
return 0
