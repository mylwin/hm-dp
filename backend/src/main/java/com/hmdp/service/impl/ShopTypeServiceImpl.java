package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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

    /**
     * 查询商铺类型列表
     *
     * @return 商铺类型列表
     */
    @Override
    public Result queryList() {
        // 1.从redis中查询商铺类型列表（zSet数据类型）
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

        // 3.不存在，查询数据库
        List<ShopType> shopTypeList = list();

        // 4.数据库中不存在，往redis中写入空值标记并返回错误状态
        if (shopTypeList.isEmpty()) {
            stringRedisTemplate.opsForZSet().add(CACHE_SHOP_TYPE_KEY, "", 0);
            stringRedisTemplate.expire(CACHE_SHOP_TYPE_KEY, CACHE_NULL_TTL, TimeUnit.MINUTES);
            return Result.fail("商铺类型不存在");
        }

        // 5.存在，将数据库信息写入redis，设置缓存时间
        for (ShopType shopType : shopTypeList) {
            stringRedisTemplate.opsForZSet().add(CACHE_SHOP_TYPE_KEY, JSONUtil.toJsonStr(shopType), shopType.getSort());
        }
        stringRedisTemplate.expire(CACHE_SHOP_TYPE_KEY, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 6.返回结果
        return Result.ok(shopTypeList);
    }
}
