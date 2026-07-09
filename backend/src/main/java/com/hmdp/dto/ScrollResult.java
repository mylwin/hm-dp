package com.hmdp.dto;

import lombok.Data;

import java.util.List;


/**
 * 滚动分页结果
 *
 * @author mylwin
 * @since 2026-05-23
 */
@Data
public class ScrollResult {
    /**
     * 列表
     */
    private List<?> list;

    /**
     * 最小时间
     */
    private Long minTime;

    /**
     * 偏移量
     */
    private Integer offset;
}
