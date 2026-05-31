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

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

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
        if (!cacheSet.isEmpty()) {
            // 将set数据类型转换为list
            List<ShopType> shopTypeList = new ArrayList<>();
            for (String json : cacheSet) {
                shopTypeList.add(JSONUtil.toBean(json, ShopType.class));
            }
            return Result.ok(shopTypeList);
        }

        // 3.不存在，从数据库中查询
        List<ShopType> shopTypeList = list();

        // 4.数据库中不存在，返回错误状态
        if (shopTypeList.isEmpty()) {
            return Result.fail("商铺类型不存在");
        }

        // 5.存在，将数据写入redis
        for (ShopType shopType : shopTypeList) {
            stringRedisTemplate.opsForZSet().add(CACHE_SHOP_TYPE_KEY, JSONUtil.toJsonStr(shopType), shopType.getSort());
        }

        // 6.返回结果
        return Result.ok(shopTypeList);
    }
}
