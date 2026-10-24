package com.hmdp.infrastructure.seckill;

import com.hmdp.constants.SeckillReservationResult;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.hmdp.constants.SeckillConstants.RESERVATION_KEY_PREFIX;

/**
 * 仅操作新命名空间，不写旧 Stream，也不按数据库库存自动覆盖 Redis。
 *
 * @author myl
 */
@Component
@RequiredArgsConstructor
// 只有在配置文件中将 reliable-components-enabled 设置为 true 才启用
@ConditionalOnProperty(prefix = "hmdp.seckill", name = "reliable-components-enabled", havingValue = "true")
public class SeckillReservationStore {
    private final StringRedisTemplate redis;
    private static final DefaultRedisScript<Long> RESERVE = new DefaultRedisScript<>();

    static {
        RESERVE.setLocation(new ClassPathResource("lua/seckill/reserve-order.lua"));
        RESERVE.setResultType(Long.class);
    }

    /**
     * 原子检查活动并预占一份库存；重复订单返回原预占结果。
     *
     * @param voucherId 券编号，用作全部键的共同 hash tag
     * @param userId    用户编号
     * @param orderId   稳定订单编号
     * @param epoch     数据库控制行中的当前代次
     * @return 已知预占结果
     * @throws IllegalStateException 脚本结果未知时抛出，调用方保留 PREPARING
     */
    public SeckillReservationResult reserve(Long voucherId, Long userId, Long orderId, Long epoch) {
        return SeckillReservationResult.fromCode(redis.execute(RESERVE, keys(voucherId, orderId),
                userId.toString(), orderId.toString(), voucherId.toString(), epoch.toString()));
    }

    /**
     * 构造新链路脚本的完整键，全部通过 KEYS 传入。
     *
     * @param voucherId 券编号
     * @param orderId   订单编号
     * @return 库存、元数据、用户归属、订单预占四个键
     */
    public static List<String> keys(Long voucherId, Long orderId) {
        String prefix = RESERVATION_KEY_PREFIX + voucherId + "}:";
        return List.of(prefix + "stock", prefix + "meta", prefix + "users", prefix + "reservation:" + orderId);
    }
}
