package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 探店笔记服务接口
 *
 * @author mylwin
 * @since 2026-05-23
 */
public interface IBlogService extends IService<Blog> {
    /**
     * 根据id查询博客
     *
     * @param id 博客id
     * @return 博客
     */
    Result queryBlogById(Long id);

    /**
     * 查询热门博客
     *
     * @param current 当前页
     * @return 热门博客
     */
    Result queryHotBlog(Integer current);

    /**
     * 点赞
     *
     * @param id 博客id
     * @return 结果
     */
    Result likeBlog(Long id);

    /**
     * 查询博客点赞的用户列表（按照时间先后顺序）
     *
     * @param id 博客id
     * @return 点赞列表
     */
    Result queryBlogLikes(Long id);
}
