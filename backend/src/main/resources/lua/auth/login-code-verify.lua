-- KEYS: 验证码、错误次数；ARGV: 输入值、最大失败次数、锁定秒数
-- 结果：0表示成功，1表示输入错误，2表示错误次数过多
-- 验证码尝试次数限制
local limit = tonumber(ARGV[2])
-- 如果错误次数达到上限，直接返回
if tonumber(redis.call('GET', KEYS[2]) or '0') >= limit then
    return 2
end
-- 验证输入值
local expected = redis.call('GET', KEYS[1])
-- 如果输入值正确，删除验证码和错误次数
if expected and ARGV[1] ~= '' and expected == ARGV[1] then
    redis.call('DEL', KEYS[1], KEYS[2])
    return 0
end
-- 增加错误次数
local failures = redis.call('INCR', KEYS[2])
-- 如果错误次数为1或达到上限，设置锁定时间，锁定时间为 10 分钟
if failures == 1 or failures >= limit then
    redis.call('EXPIRE', KEYS[2], ARGV[3])
end
-- 如果错误次数达到上限，返回2
if failures >= limit then
    return 2
end
return 1
