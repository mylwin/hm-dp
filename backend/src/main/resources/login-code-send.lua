-- KEYS: 验证码、发送冷却、错误次数；ARGV: 验证码、有效秒数、冷却秒数
-- 如果错误次数大于等于5次，则返回2
-- 结果：0表示成功，1表示发送过于频繁，2表示错误次数过多
if tonumber(redis.call('GET', KEYS[3]) or '0') >= 5 then
    return 2
end
-- 如果发送冷却存在，则返回1
if redis.call('EXISTS', KEYS[2]) == 1 then
    return 1
end
-- 验证码保留时间为 2 分钟
redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
-- 发送冷却时间为 1 分钟
redis.call('SET', KEYS[2], '1', 'EX', ARGV[3])
return 0
