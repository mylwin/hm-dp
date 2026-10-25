#!/bin/sh
set -eu

export REDISCLI_AUTH="$REDIS_PASSWORD"
[ "$(redis-cli --raw ping)" = "PONG" ] || exit 1

# 秒杀已使用 RocketMQ，健康检查只检测连通性，不再创建旧订单 Stream 或消费组。
