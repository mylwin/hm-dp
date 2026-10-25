package com.hmdp.infrastructure.seckill;

import com.hmdp.constants.SeckillReservationResult;
import lombok.RequiredArgsConstructor;
import com.hmdp.exception.SeckillConsistencyException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static com.hmdp.constants.SeckillConstants.RESERVATION_KEY_PREFIX;

/**
 * 通过 Lua 准备活动库存、暂扣库存，并确认、释放或核对某个订单的预占记录。
 * 预占表示先暂扣一份 Redis 库存；epoch 是库存版本号，用于阻止旧任务修改新版本数据。
 * 只操作新命名空间，不写旧 Stream，也不按数据库库存覆盖已使用的 Redis 库存。
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
    private static final DefaultRedisScript<Long> INITIALIZE = new DefaultRedisScript<>();
    private static final DefaultRedisScript<Long> FINISH = new DefaultRedisScript<>();
    private static final DefaultRedisScript<String> VERIFY = new DefaultRedisScript<>();

    static {
        RESERVE.setLocation(new ClassPathResource("lua/seckill/reserve-order.lua"));
        RESERVE.setResultType(Long.class);
        INITIALIZE.setLocation(new ClassPathResource("lua/seckill/initialize-campaign.lua"));
        INITIALIZE.setResultType(Long.class);
        FINISH.setLocation(new ClassPathResource("lua/seckill/finish-reservation.lua"));
        FINISH.setResultType(Long.class);
        VERIFY.setLocation(new ClassPathResource("lua/seckill/verify-reservation.lua"));
        VERIFY.setResultType(String.class);
    }

    /**
     * 把原订单的 Redis 预占改为已确认，或释放该订单之前暂扣的一份库存。
     * 确认只写 CONFIRMED；释放只在 RESERVED 首次处理时回补一份，已 RELEASED 重复执行不再加库存。
     * 缺失预占只有 allowMissing=true 才能写 RELEASED 零数量终态，阻止迟到的原订单重新扣库。
     * 调用前由业务层检查数据库：确认要求订单已成功，释放要求请求已关闭且没有原订单。
     *
     * @param voucherId 预占所属券编号，所有脚本键使用此券的相同 hash tag
     * @param userId 预占所属用户编号，脚本还要核对该用户当前归属的订单号
     * @param orderId 请求最初绑定的订单编号，重试时保持不变
     * @param epoch 请求预占时记录的库存版本号，必须与 Redis 元数据和预占记录一致
     * @param confirm true 确认原预占；false 释放原预占，不表示直接取消数据库订单
     * @param allowMissing 仅原 PREPARING 请求被显式关闭时为 true；允许无预占时写零数量终态，不凭空加库存
     * @throws SeckillConsistencyException 脚本返回 0，表示身份、代次、状态、数量或归属不符合要求
     * @throws IllegalStateException 脚本结果不是已知的 0 或 1，不能确定处理完成
     */
    public void finish(Long voucherId, Long userId, Long orderId, Long epoch, boolean confirm, boolean allowMissing) {
        Long result = redis.execute(FINISH, keys(voucherId, orderId), userId.toString(), orderId.toString(),
                voucherId.toString(), epoch.toString(), confirm ? "CONFIRM" : "RELEASE", allowMissing ? "1" : "0");
        if (Long.valueOf(0).equals(result)) {
            throw new SeckillConsistencyException("预占确认或释放数据冲突");
        }
        if (!Long.valueOf(1).equals(result)) {
            throw new IllegalStateException("预占处理结果未确认，保留任务重试");
        }
    }

    /**
     * 只读检查原订单是否仍有身份和代次一致的有效预占，供恢复或重投前判断。
     * 检查库存格式、元数据、用户归属及预占数量，只接受 RESERVED 或 CONFIRMED；不修改 Redis，也不再次扣库存。
     * CONFIRMED 只是 Redis 的记录，调用方仍须核对数据库原订单确实存在。
     *
     * @param voucherId 需要核对的券编号
     * @param userId 原请求用户编号，必须与预占记录一致
     * @param orderId 原请求订单编号，必须仍是 users 中该用户的归属订单
     * @param epoch 原请求的库存版本号，必须与 meta 和预占记录一致
     * @return RESERVED 表示仍在预占；CONFIRMED 表示 Redis 已确认，均已通过本脚本的字段核对
     * @throws SeckillConsistencyException 脚本返回 CONFLICT，包括键缺失、归属、代次、数量或状态异常
     * @throws IllegalStateException 脚本返回未知结果，不能据此判断预占有效
     */
    public String verify(Long voucherId, Long userId, Long orderId, Long epoch) {
        String state = redis.execute(VERIFY, keys(voucherId, orderId), userId.toString(), orderId.toString(),
                voucherId.toString(), epoch.toString());
        if ("CONFLICT".equals(state)) {
            throw new SeckillConsistencyException("已受理请求的有效预占缺失或冲突");
        }
        if (!"RESERVED".equals(state) && !"CONFIRMED".equals(state)) {
            throw new IllegalStateException("预占核对结果未确认，保留请求重试");
        }
        return state;
    }

    /**
     * 为首次发布的新券准备 Redis 库存和活动时间，或核对上次已经准备好的数据。
     * 允许创建时也只有三个键全部不存在才写入；已有键只核对，不覆盖库存或补齐部分缺失的键。
     * 时间按项目数据库约定由 UTC 转为毫秒；返回后由调用方提交数据库 READY，脚本自身不开放数据库活动。
     *
     * @param voucherId 已落库的券编号，用于构造库存、元数据和用户归属键
     * @param epoch 数据库活动控制行的库存版本号，重试时沿用同一值
     * @param stock 数据库非负库存；首次发布核对须仍等于初始库存，已发布核对不比较库存相等
     * @param beginTime 从数据库重新读取的 UTC 活动开始时间，非 null
     * @param endTime 从数据库重新读取的 UTC 活动结束时间，非 null 且晚于开始时间
     * @param allowCreate true 仅供数据库 PAUSED/PENDING 的首次发布；false 只核对，全部缺键也不重新创建
     * @throws SeckillConsistencyException 脚本返回 0，包括错误类型、TTL、代次、时间或库存冲突
     * @throws IllegalStateException 脚本返回未知结果，发布尚未确认，应沿用原券编号重试
     */
    public void initializeCampaign(Long voucherId, Long epoch, Integer stock, LocalDateTime beginTime,
                                   LocalDateTime endTime, boolean allowCreate) {
        // 初始化活动库存和元数据
        Long result = redis.execute(INITIALIZE, keys(voucherId, 0L).subList(0, 3), stock.toString(),
                epoch.toString(), String.valueOf(beginTime.toInstant(ZoneOffset.UTC).toEpochMilli()),
                String.valueOf(endTime.toInstant(ZoneOffset.UTC).toEpochMilli()), allowCreate ? "1" : "0");
        if (Long.valueOf(0L).equals(result)) {
            throw new SeckillConsistencyException("活动 Redis 数据缺失或冲突，禁止覆盖库存");
        }
        if (!Long.valueOf(1L).equals(result)) {
            throw new IllegalStateException("活动初始化结果未确认，请使用原券编号重试");
        }
    }

    /**
     * 为一个抢购请求原子检查活动时间和购买资格，并暂扣一份 Redis 库存。
     * 首次成功记录 RESERVED 和用户归属；同一订单重复预占返回原结果，不再次扣库存。
     * 明确拒绝会保存拒绝记录；调用方随后把受理状态与建单 Outbox 在同一数据库事务中提交。
     *
     * @param voucherId 原请求的券编号，也是所有脚本键共同的 hash tag
     * @param userId 原请求用户编号，用于检查一人一份预占资格
     * @param orderId 原请求固定的订单编号，响应丢失后重试也不能更换
     * @param epoch 数据库活动控制行的当前库存版本号，用于拒绝旧版本预占
     * @return 预占结果；RESERVED 表示已暂扣或已有相同预占，其他枚举表示拒绝、未就绪、终态或身份冲突
     * @throws IllegalStateException 脚本返回未知结果；调用方不能推断未扣库，应保留 PREPARING 等待核对
     */
    public SeckillReservationResult reserve(Long voucherId, Long userId, Long orderId, Long epoch) {
        return SeckillReservationResult.fromCode(redis.execute(RESERVE, keys(voucherId, orderId),
                userId.toString(), orderId.toString(), voucherId.toString(), epoch.toString()));
    }

    /**
     * 为同一张券生成库存、活动元数据、用户归属和原订单预占的完整 Redis 键。
     * 券编号用花括号包成 hash tag，保证一次 Lua 使用的键位于 Redis Cluster 同一槽。
     *
     * @param voucherId 键所属的券编号
     * @param orderId 预占记录所属的原订单编号；只取前三个键时可传占位编号
     * @return 固定四项列表，依次为 stock、meta、users、reservation:orderId，均已包含项目命名空间
     */
    public static List<String> keys(Long voucherId, Long orderId) {
        String prefix = RESERVATION_KEY_PREFIX + voucherId + "}:";
        return List.of(prefix + "stock", prefix + "meta", prefix + "users", prefix + "reservation:" + orderId);
    }
}
