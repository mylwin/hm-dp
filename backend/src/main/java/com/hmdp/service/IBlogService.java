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

    /**
     * 根据用户id查询博客列表
     *
     * @param id      用户id
     * @param current 当前页
     * @return 博客列表
     */
    Result queryBlogByUserId(Long id, Integer current);

    /**
     * 查询关注的用户博客（滚动分页）
     *
     * @param max    最新时间戳
     * @param offset 偏移量
     * @return 博客列表
     */
    Result queryBlogOfFollow(Long max, Integer offset);

    /**
     * 保存博客
     *
     * @param blog 博客
     * @return 结果
     */
    Result saveBlog(Blog blog);
}
