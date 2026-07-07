package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

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

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 根据id查询探店笔记
     *
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

        // 3.添加用户是否对博客点赞
        setLikedStatus(blog);
        return Result.ok(blog);
    }

    /**
     * 查询热门探店笔记
     *
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
     * 点赞
     *
     * @param id 博客id
     * @return 结果
     */
    @Override
    public Result likeBlog(Long id) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }

        // 2.判断用户是否已经点赞
        String key = BLOG_LIKED_KEY + id;
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, user.getId().toString());

        // 3.如果未点赞，则可以点赞
        if (BooleanUtil.isFalse(isMember)) {
            // 3.1 数据库操作，点赞数+1
            boolean success = update().setSql("liked = liked + 1").eq("id", id).update();
            // 3.2 保存用户id到redis集合中
            if (success) {
                stringRedisTemplate.opsForSet().add(key, user.getId().toString());
            }
        } else {
            // 4.如果已经点赞，则取消点赞
            // 4.1 数据库操作，点赞数-1
            boolean success = update().setSql("liked = liked - 1").eq("id", id).update();
            // 4.2 从redis集合中删除用户id
            if (success) {
                stringRedisTemplate.opsForSet().remove(key, user.getId().toString());
            }
        }

        // 5.返回结果
        return Result.ok();
    }

    /**
     * 判断用户是否在点赞列表中
     *
     * @param id 博客id
     * @return true表示在点赞列表中，false表示不在点赞列表中
     */
    private Boolean isMember(Long id){
        // 获取用户
        UserDTO user = UserHolder.getUser();
        if (user == null) return false;

        // 判断用户是否在点赞列表中
        String key = BLOG_LIKED_KEY + id;
        return stringRedisTemplate.opsForSet().isMember(key, user.getId().toString());
    }

    /**
     * 设置用户是否点赞的状态
     *
     * @param blog 博客
     */
    private void setLikedStatus(Blog blog) {
        // 用户是否点赞
        Boolean isMember = isMember(blog.getId());

        // 设置用户是否点赞的状态
        blog.setIsLike(BooleanUtil.isTrue(isMember));
    }

    /**
     * 查询博客用户
     *
     * @param blog 博客
     */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
