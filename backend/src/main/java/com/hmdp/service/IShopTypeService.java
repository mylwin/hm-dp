package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * 商铺类型服务接口
 *
 * @author mylwin
 * @since 2026-05-23
 */
public interface IShopTypeService extends IService<ShopType> {

    /**
     * 查询商铺类型列表
     *
     * @return 商铺类型列表结果
     */
    Result queryList();
}
