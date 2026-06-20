package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    @Autowired
    private CacheClient cacheClient;

    // private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 根据id查询商铺信息
     *
     * @param id 商铺id
     * @return 商铺信息
     */
    @Override
    public Result queryById(Long id) {
        // 缓存空值解决缓存穿透问题
        // Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 利用互斥锁解决缓存击穿问题
        Shop shop = cacheClient.queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 利用逻辑过期解决缓存击穿问题
        // Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 返回结果
        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);
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

    /**
     * 根据id查询商铺信息（解决缓存击穿问题（热点key）-逻辑过期）
     *
     * @param id 商铺id
     * @return 商铺信息
     */
    // @Override
    /*public Result queryById2(Long id) {
        // 1.从 redis 查询商铺信息
        String cacheShopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);

        // 2.缓存未命中，直接返回错误状态
        if (StrUtil.isBlank(cacheShopJson)) {
            return Result.fail("店铺不存在");
        }

        // 3.缓存命中
        RedisData redisData = JSONUtil.toBean(cacheShopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 3.1缓存未过期，返回店铺信息
        if (expireTime.isAfter(LocalDateTime.now())) {
            return Result.ok(shop);
        }

        // 4.缓存过期，尝试获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLocked = tryLock(lockKey);
        if (isLocked) {
            // 4.1获取锁之后再次检测缓存是否已被其他线程重建（双重检测）
            String doubleCheckShopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
            if (StrUtil.isNotBlank(doubleCheckShopJson)) {
                RedisData doubleCheckData = JSONUtil.toBean(doubleCheckShopJson, RedisData.class);
                if (doubleCheckData.getExpireTime().isAfter(LocalDateTime.now())) {
                    // 缓存已被其他线程重建且未过期，释放锁并返回
                    unlock(lockKey);
                    Shop freshShop = JSONUtil.toBean((JSONObject) doubleCheckData.getData(), Shop.class);
                    return Result.ok(freshShop);
                }
            }
            // 4.2缓存确实需要重建，开启独立线程重建缓存，重建完成后释放锁
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    saveShop2Redis(id, CACHE_SHOP_TTL);
                } finally {
                    unlock(lockKey);
                }
            });
        }
        // 5.返回旧的店铺信息（逻辑过期方案的核心：优先保证可用性）
        return Result.ok(shop);
    }*/
    /**
     * 根据id查询商铺信息（解决缓存穿透和缓存击穿问题-互斥锁）
     *
     * @param id 商铺id
     * @return 商铺信息
     */
    // @Override
    /*public Result queryById1(Long id) {
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
    }*/
    /**
     * 尝试获取锁
     *
     * @param key redis中锁的key
     * @return 是否获取成功
     */

    /*private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "lock", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }*/
    /**
     * 释放锁
     *
     * @param key redis中锁的key
     */

    /*private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }*/
    /**
     * 将商铺信息保存到redis中，并设置逻辑过期时间
     *
     * @param id 商铺id
     */

    /*public void saveShop2Redis(Long id, Long expireTime) {
        // 1.根据id查询店铺信息
        Shop shop = getById(id);

        // 2.封装逻辑过期时间
        RedisData<Shop> redisData = new RedisData<>();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireTime));

        // 睡眠200毫秒
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        // 3.添加到redis中
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }*/
}
