package com.hmdp.constants;

/** 秒杀库存与订单消息流常量，保留原有取值及时间单位。 */
public final class SeckillConstants {
    private SeckillConstants() {
    }

    public static final String SECKILL_STOCK_KEY = "hmdp:seckill:stock:";

    public static final String ORDER_MQ = "hmdp:stream:orders";
}
