package com.hmdp.constants;

/**
 * 商铺地理位置索引常量，保留原有取值及时间单位。
 * @author myl
 */
public final class ShopConstants {
    private ShopConstants() {
    }

    public static final String SHOP_GEO_KEY = "hmdp:shop:geo:";

    public static final String SHOP_BLOOM_KEY_PREFIX = "hmdp:bloom:shop:";
    public static final String SHOP_BLOOM_STATE_KEY_PREFIX = "hmdp:bloom:shop:state:";

    /**
     * 跨版本共享：构建持写锁，新增持读锁，防止扫描与新增之间漏同步。
     */
    public static final String SHOP_BLOOM_LIFECYCLE_LOCK = "hmdp:lock:bloom:shop:lifecycle";
    public static final int SHOP_BLOOM_SYNC_PROTOCOL = 2;
}
