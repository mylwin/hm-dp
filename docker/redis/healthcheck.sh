#!/bin/sh
set -eu

export REDISCLI_AUTH="$REDIS_PASSWORD"
[ "$(redis-cli --raw ping)" = "PONG" ] || exit 1

# 后端启动时会监听这个消费组。幂等创建，已有消息和消费进度保持不变。
result=$(redis-cli --raw XGROUP CREATE hmdp:stream:orders g1 0 MKSTREAM 2>&1)
case "$result" in
  OK|BUSYGROUP*) exit 0 ;;
  *) printf '%s\n' "$result" >&2; exit 1 ;;
esac
