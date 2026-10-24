package com.hmdp.constants;

/**
 * 秒杀库存与订单消息流常量，保留原有取值及时间单位。
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
}
