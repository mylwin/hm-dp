---@diagnostic disable: undefined-global
-- 1.参数列表
-- 1.1 优惠券id
local voucherId = ARGV[1]
-- 1.2 用户id
local userId = ARGV[2]

-- 2.数据key
-- 2.1 优惠券库存key
local seckillStockKey = "hmdp:seckill:stock:" .. voucherId
-- 2.2 订单key
local seckillOrderKey = "hmdp:seckill:order:" .. voucherId

-- 3.业务逻辑
-- 3.1 判断库存是否充足
if tonumber(redis.call("get", seckillStockKey)) <= 0 then
    -- 3.2 库存不足，返回1
    return 1
end
-- 3.3 判断用户是否已经下单
if redis.call("sismember", seckillOrderKey, userId) == 1 then
    -- 3.4 不能重复下单，返回2
    return 2
end
-- 3.5 扣减库存
redis.call("incrby", seckillStockKey, -1)
-- 3.6 将userId保存到优惠券的set集合中
redis.call("sadd", seckillOrderKey, userId)
-- 3.7 秒杀成功，返回0
return 0