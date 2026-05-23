package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;


/**
 * 通用结果类
 *
 * @author mylwin
 * @since 2026-05-23
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result {
    /**
     * 是否成功
     */
    private Boolean success;

    /**
     * 错误信息
     */
    private String errorMsg;

    /**
     * 数据
     */
    private Object data;

    /**
     * 总条数
     */
    private Long total;

    /**
     * 成功
     * @return 通用结果对象
     */
    public static Result ok(){
        return new Result(true, null, null, null);
    }
    /**
     * 成功，包含数据
     * @param data 数据
     * @return 通用结果对象
     */
    public static Result ok(Object data){
        return new Result(true, null, data, null);
    }
    /**
     * 成功，包含数据和总条数
     * @param data 数据
     * @param total 总条数
     * @return 通用结果对象
     */
    public static Result ok(List<?> data, Long total){
        return new Result(true, null, data, total);
    }
    /**
     * 失败
     * @param errorMsg 错误信息
     * @return 通用结果对象
     */
    public static Result fail(String errorMsg){
        return new Result(false, errorMsg, null, null);
    }
}
