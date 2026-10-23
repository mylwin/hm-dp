package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 商铺服务接口
 *
 * @author mylwin
 * @since 2026-05-23
 */
public interface IShopService extends IService<Shop> {

    /**
     * 新增商铺，同时维护布隆快照与详情缓存。
     * 必须在活动事务之外调用，数据库提交后才清理缓存并释放锁。
     *
     * @param shop 待新增的商铺数据，不能为 null；指定编号时必须为正数
     * @return 成功结果，data 为新增商铺的正数编号
     * @throws IllegalArgumentException 商铺为空或指定编号不是正数
     * @throws IllegalStateException 已存在活动事务、插入失败或布隆快照异常
     * @throws com.hmdp.exception.CacheRebuildBusyException 等锁超时或线程等待锁时被中断
     */
    Result createShop(Shop shop);

    /**
     * 根据正数编号查询商铺，缓存未命中时执行可配置的布隆准入。
     *
     * @param id 商铺编号，必须为正数
     * @return 成功结果包含商铺信息；编号无效或商铺不存在时返回失败结果
     */
    Result queryById(Long id);

    /**
     * 更新商铺信息
     *
     * @param shop 商铺数据
     * @return 无
     */
    Result updateShop(Shop shop);

    /**
     * 根据类型查询商铺信息
     *
     * @param typeId  商铺类型
     * @param current 页码
     * @param x       经度
     * @param y       纬度
     * @return 商铺信息
     */
    Result queryShopByType(Integer typeId, Integer current, Double x, Double y);
}
