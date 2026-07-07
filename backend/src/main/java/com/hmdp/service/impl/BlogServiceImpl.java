package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 探店笔记服务实现类
 *
 * @author mylwin
 * @since 2026-05-23
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Autowired
    private IUserService userService;

    /**
     * 根据id查询探店笔记
     * @param id 博客id
     * @return 博客
     */
    @Override
    public Result queryBlogById(Long id) {
        // 1.获取博客
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("博客不存在");
        }
        
        // 2.获取用户
        queryBlogUser(blog);
        return Result.ok(blog);
    }

    /**
     * 查询热门探店笔记
     * @param current 当前页
     * @return 热门探店笔记
     */
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(this::queryBlogUser);
        return Result.ok(records);
    }

    /**
     * 查询博客用户
     * @param blog 博客
     */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
