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

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

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
        if(StrUtil.isNotBlank(cacheShopJson)){
            // 2.1将数据转为对象
            Shop shop = BeanUtil.toBean(cacheShopJson, Shop.class);
            return Result.ok(shop);
        }

        // 3.不存在，查询数据库
        Shop shop = getById(id);

        // 4.数据库中不存在，返回错误状态
        if(shop == null){
            return Result.fail("店铺不存在");
        }

        // 5.存在，将数据库信息写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop));

        // 6.返回结果
        return Result.ok(shop);
    }
}
