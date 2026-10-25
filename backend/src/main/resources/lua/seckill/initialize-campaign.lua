-- 为首次发布的新券准备库存和活动时间；重复调用只核对已有数据，不重置已被使用的库存。
-- KEYS：库存 String、活动元数据 Hash、用户归属 Hash；同一券 hash tag。
-- ARGV：初始库存、代次、UTC 开始/结束毫秒、是否允许首次创建（1/0）。
-- 返回：1 就绪（新建或核对通过），0 数据缺失、冲突或不允许初始化。
local stockKey, metaKey, usersKey = KEYS[1], KEYS[2], KEYS[3]
local stockText, epoch, beginAt, endAt, allowCreate = ARGV[1], ARGV[2], ARGV[3], ARGV[4], ARGV[5]
local stock = tonumber(stockText)

-- 先检查传入库存是范围内的非负整数、开始早于结束，防止写入无法参与预占的数据。
if not stock or not string.match(stockText, '^%d+$') or stock > 2147483647
    or not tonumber(beginAt) or not tonumber(endAt) or tonumber(beginAt) >= tonumber(endAt) then return 0 end

local stockType = redis.call('TYPE', stockKey).ok
local metaType = redis.call('TYPE', metaKey).ok
local usersType = redis.call('TYPE', usersKey).ok
-- 三个键全部不存在时还要看 allowCreate；已发布活动缺键也不能在只读核对时重新灌入库存。
if stockType == 'none' and metaType == 'none' and usersType == 'none' then
    -- allowCreate 由业务层根据 PAUSED/PENDING 决定；只读核对不具备创建资格。
    if allowCreate ~= '1' then return 0 end
    -- 只为从未开放的新活动写入；重试已有键时不执行 SET。
    redis.call('SET', stockKey, stockText)
    redis.call('HSET', metaKey, 'ready', '1', 'epoch', epoch, 'beginAt', beginAt, 'endAt', endAt)
    return 1
end

-- 部分缺失、错误类型、过期键均交给恢复流程，不在此处拼补或覆盖。
-- 已有库存和元数据必须完整且类型正确；用户归属可以不存在，但存在时必须是 Hash。
if stockType ~= 'string' or metaType ~= 'hash' or (usersType ~= 'none' and usersType ~= 'hash') then return 0 end
-- 已有键必须无过期时间（PTTL=-1），有 TTL 或已消失都不能当作可靠的活动数据继续开放。
if redis.call('PTTL', stockKey) ~= -1 or redis.call('PTTL', metaKey) ~= -1
    or (usersType == 'hash' and redis.call('PTTL', usersKey) ~= -1) then return 0 end
-- 必须是同一库存版本、同一活动时间且已准备好的元数据，不能借重试覆盖另一版活动。
if redis.call('HGET', metaKey, 'ready') ~= '1' or redis.call('HGET', metaKey, 'epoch') ~= epoch
    or redis.call('HGET', metaKey, 'beginAt') ~= beginAt or redis.call('HGET', metaKey, 'endAt') ~= endAt then return 0 end

local currentText = redis.call('GET', stockKey)
local current = tonumber(currentText)
-- 已有库存允许为 0，但必须是范围内的非负整数字符串，异常时不尝试改正。
if not current or not string.match(currentText, '^%d+$') or current > 2147483647 then return 0 end
-- 待发布活动尚不能被受理，库存必须仍等于数据库初始库存且没有用户归属。
if allowCreate == '1' and (currentText ~= stockText or usersType ~= 'none') then return 0 end
return 1
