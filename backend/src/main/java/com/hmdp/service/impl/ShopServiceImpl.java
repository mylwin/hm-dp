package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * 商铺服务实现类
 *
 * @author mylwin
 * @since 2026-05-23
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 根据id查询商铺信息（解决缓存穿透和缓存击穿问题-互斥锁）
     *
     * @param id 商铺id
     * @return 商铺信息
     */
    @Override
    public Result queryById(Long id) {
        // 1.从 redis 查询商铺信息
        String cacheShopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);

        // 2.存在，直接返回
        if (StrUtil.isNotBlank(cacheShopJson)) {
            Shop shop = JSONUtil.toBean(cacheShopJson, Shop.class);
            return Result.ok(shop);
        }

        // 如果缓存中的是空值，直接返回错误状态（缓存穿透保护）
        if (cacheShopJson != null) {
            return Result.fail("店铺不存在");
        }

        // 3.缓存未命中，尝试获取互斥锁重建缓存（缓存击穿保护）
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLocked = tryLock(lockKey);
        if (!isLocked) {
            // 3.1.未获取到锁，休眠后重试
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return queryById(id);
        }

        // 3.2.获取到锁，再次检测redis缓存是否存在（双重检测）
        Shop shop = null;
        try {
            cacheShopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
            if (StrUtil.isNotBlank(cacheShopJson)) {
                // 其他线程已重建缓存，直接返回
                shop = JSONUtil.toBean(cacheShopJson, Shop.class);
                return Result.ok(shop);
            }
            if (cacheShopJson != null) {
                return Result.fail("店铺不存在");
            }

            // 4.缓存确实不存在，查询数据库
            shop = getById(id);

            // 5.数据库中不存在，往redis中写入空并返回错误状态
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return Result.fail("店铺不存在");
            }

            // 6.存在，将数据库信息写入redis，设置缓存时间
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } finally {
            // 7.释放锁（只有获取到锁的线程才会执行到这里）
            unlock(lockKey);
        }
        // 8.返回结果
        return Result.ok(shop);
    }

    /**
     * 尝试获取锁
     * @param key redis中锁的key
     * @return 是否获取成功
     */
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "lock", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     * @param key redis中锁的key
     */
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    /**
     * 更新商铺信息
     *
     * @param shop 商铺数据
     * @return 无
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result updateShop(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 1.更新数据库
        updateById(shop);

        // 2.删除 redis 缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);

        return Result.ok();
    }
}
