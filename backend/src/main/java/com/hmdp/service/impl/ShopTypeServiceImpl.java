package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.hmdp.utils.CacheTtl;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * 商铺类型服务实现类
 *
 * @author mylwin
 * @since 2026-05-23
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    /**
     * 查询商铺类型列表
     *
     * @return 商铺类型列表
     */
    @Override
    public Result queryList() {
        Result cached = readCache();
        if (cached != null) {
            return cached;
        }

        RLock lock = redissonClient.getLock(LOCK_KEY + "shop:type");
        boolean locked = false;
        try {
            // 最多等待1秒，使用Redisson看门狗续期，避免慢查询导致锁提前过期。
            locked = lock.tryLock(1, TimeUnit.SECONDS);
            // 等待期间可能已有其他请求完成重建，获得锁后也必须二次检查。
            cached = readCache();
            if (cached != null) {
                return cached;
            }
            if (!locked) {
                return Result.fail("操作过于频繁，请稍后再试");
            }
            return rebuildCache();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.fail("操作被中断，请稍后再试");
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 缓存未命中返回null，空值标记仍按已命中处理。
     */
    private Result readCache() {
        Set<String> cacheSet = stringRedisTemplate.opsForZSet()
                .range(CACHE_SHOP_TYPE_KEY, 0, -1);

        // 2.存在，直接返回
        if (cacheSet != null && !cacheSet.isEmpty()) {
            // 如果缓存中的是空值标记，直接返回错误状态
            if (cacheSet.size() == 1 && "".equals(cacheSet.iterator().next())) {
                return Result.fail("商铺类型不存在");
            }
            // 将set数据类型转换为list
            List<ShopType> shopTypeList = new ArrayList<>();
            for (String json : cacheSet) {
                shopTypeList.add(JSONUtil.toBean(json, ShopType.class));
            }
            return Result.ok(shopTypeList);
        }
        return null;
    }

    /**
     * 仅由持锁请求查询数据库并重建缓存。
     */
    private Result rebuildCache() {
        List<ShopType> shopTypeList = list();

        // 4.数据库中不存在，往redis中写入空值标记并返回错误状态
        if (shopTypeList.isEmpty()) {
            stringRedisTemplate.opsForZSet().add(CACHE_SHOP_TYPE_KEY, "", 0);
            long ttlSeconds = CacheTtl.withJitter(CACHE_NULL_TTL, TimeUnit.MINUTES, CACHE_NULL_TTL_JITTER_SECONDS);
            stringRedisTemplate.expire(CACHE_SHOP_TYPE_KEY, ttlSeconds, TimeUnit.SECONDS);
            return Result.fail("商铺类型不存在");
        }

        // 5.存在，将数据库信息写入redis，设置缓存时间
        // 单条ZADD写入完整列表，避免其他请求读到逐条写入的部分分类。
        Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>();
        for (ShopType shopType : shopTypeList) {
            tuples.add(new DefaultTypedTuple<>(JSONUtil.toJsonStr(shopType), shopType.getSort().doubleValue()));
        }
        stringRedisTemplate.opsForZSet().add(CACHE_SHOP_TYPE_KEY, tuples);
        long ttlSeconds = CacheTtl.withJitter(CACHE_SHOP_TTL, TimeUnit.MINUTES, CACHE_TTL_JITTER_SECONDS);
        stringRedisTemplate.expire(CACHE_SHOP_TYPE_KEY, ttlSeconds, TimeUnit.SECONDS);

        // 6.返回结果
        return Result.ok(shopTypeList);
    }
}
