-- uid / 刷新凭证摘要 / 昵称 / 头像 / 七天绝对到期毫秒时间
-- 结果：1表示成功，0表示失败
-- 如果存在则返回0
if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
redis.call('HSET', KEYS[1], 'uid', ARGV[1], 'refreshHash', ARGV[2],
    'nickName', ARGV[3], 'icon', ARGV[4], 'expiresAt', ARGV[5])
redis.call('PEXPIREAT', KEYS[1], ARGV[5])
return 1
