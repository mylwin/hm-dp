package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
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
     * 根据id查询商铺信息
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

        // 如果缓存中的是空值，直接返回错误状态
        if (cacheShopJson != null) {
            return Result.fail("店铺不存在");
        }

        // 3.不存在，查询数据库
        Shop shop = getById(id);

        // 4.数据库中不存在，往redis中写入空并返回错误状态
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return Result.fail("店铺不存在");
        }

        // 5.存在，将数据库信息写入redis，设置缓存时间
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 6.返回结果
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
}
