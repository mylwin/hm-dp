package com.hmdp.infrastructure.redis;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

/** 验证订单号生成器能通过 Spring 注入 Redis 并生成不同编号，不连接真实 Redis。 */
class RedisIdGeneratorTest {

    /** 构造器注入 Redis 后，连续申请的订单号应为不同的正数。 */
    @Test
    void injectsRedisAndGeneratesDistinctOrderIds() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(startsWith("hmdp:icr:voucher_order:"))).thenReturn(1L, 2L);

        new ApplicationContextRunner()
                .withUserConfiguration(RedisIdGenerator.class)
                .withBean(StringRedisTemplate.class, () -> redis)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RedisIdGenerator generator = context.getBean(RedisIdGenerator.class);
                    long first = generator.getNextId("voucher_order");
                    long second = generator.getNextId("voucher_order");
                    assertThat(first).isPositive();
                    assertThat(second).isPositive().isNotEqualTo(first);
                    verify(values, times(2)).increment(startsWith("hmdp:icr:voucher_order:"));
                });
    }
}
