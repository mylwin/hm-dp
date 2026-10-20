-- 只轮换现存会话，绝不重新创建已退出的会话，也不延长原有 TTL。
-- 如果会话不存在，则返回 nil。
if redis.call('PTTL', KEYS[1]) <= 0 then return nil end
-- 检查 refreshHash 是否匹配，不匹配则返回 nil。
if redis.call('HGET', KEYS[1], 'refreshHash') ~= ARGV[1] then return nil end
-- 更新 refreshHash。
redis.call('HSET', KEYS[1], 'refreshHash', ARGV[2])
return redis.call('HMGET', KEYS[1], 'uid', 'nickName', 'icon', 'expiresAt')
