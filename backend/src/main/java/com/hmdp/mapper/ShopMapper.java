package com.hmdp.mapper;

import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 商铺 Mapper
 *
 * @author myl
 * @since 2026-05-23
 */
public interface ShopMapper extends BaseMapper<Shop> {

    @Select("SELECT COALESCE(MAX(id), 0) FROM tb_shop")
    long selectMaxShopIdForBloom();

    @Select("SELECT id FROM tb_shop WHERE id > #{afterId} AND id <= #{upperBound} ORDER BY id LIMIT #{limit}")
    List<Long> selectShopIdsForBloom(@Param("afterId") long afterId,
                                    @Param("upperBound") long upperBound,
                                    @Param("limit") int limit);
}
