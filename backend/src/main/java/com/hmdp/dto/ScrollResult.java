package com.hmdp.dto;

import lombok.Data;

import java.util.List;


/**
 * 分页结果
 *
 * @author mylwin
 * @since 2026-05-23
 */
@Data
public class ScrollResult {
    private List<?> list;
    private Long minTime;
    private Integer offset;
}
