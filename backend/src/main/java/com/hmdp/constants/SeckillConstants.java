package com.hmdp.constants;

/**
 * 秒杀库存与订单消息流常量，保留原有取值及时间单位。
 *
 * @author myl
 */
public final class SeckillConstants {
    private SeckillConstants() {
    }

    public static final String SECKILL_STOCK_KEY = "hmdp:seckill:stock:";

    public static final String ORDER_MQ = "hmdp:stream:orders";

    /**
     * 新预占命名空间，券编号跟在左花括号后，全部脚本键使用同一 hash tag。
     */
    public static final String RESERVATION_KEY_PREFIX = "hmdp:seckill:{";

    public static final String CREATE_ORDER_EVENT_TYPE = "CREATE_ORDER";

    /**
     * 建单提交后待执行的 Redis 预占确认任务。
     */
    public static final String CONFIRM_RESERVATION_EVENT_TYPE = "CONFIRM_RESERVATION";

    /**
     * 请求关闭后待执行的 Redis 预占释放任务。
     */
    public static final String RELEASE_RESERVATION_EVENT_TYPE = "RELEASE_RESERVATION";
}
