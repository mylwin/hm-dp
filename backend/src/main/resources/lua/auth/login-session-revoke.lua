-- 缺失、旧凭证和重复退出均为幂等空操作。
if redis.call('HGET', KEYS[1], 'refreshHash') ~= ARGV[1] then return 0 end
return redis.call('DEL', KEYS[1])
