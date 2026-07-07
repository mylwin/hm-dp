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
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.LOCK_KEY;

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

    @Autowired
    private RedissonClient redissonClient;

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
        records.forEach(blog -> {
            queryBlogUser(blog);
            setLikedStatus(blog);
        });
        return Result.ok(records);
    }

    /**
     * 点赞/取消点赞（Redisson分布式锁保证同一用户串行，Toggle语义安全）
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

        String userId = user.getId().toString();
        // 锁粒度：用户+博客，不同用户互不影响
        String lockKey = LOCK_KEY + "blog:like:" + id + ":" + userId;
        RLock lock = redissonClient.getLock(lockKey);
        try {
            // 尝试获取锁，最多等待1秒，锁3秒后自动释放（防止死锁）
            if (!lock.tryLock(1, 3, TimeUnit.SECONDS)) {
                return Result.fail("操作过于频繁，请稍后再试");
            }

            String key = BLOG_LIKED_KEY + id;
            // 在锁内检查当前状态，保证Toggle语义正确
            Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId);

            if (BooleanUtil.isFalse(isMember)) {
                // 未点赞 → 点赞
                update().setSql("liked = liked + 1").eq("id", id).update();
                stringRedisTemplate.opsForSet().add(key, userId);
            } else {
                // 已点赞 → 取消点赞
                update().setSql("liked = liked - 1").eq("id", id).update();
                stringRedisTemplate.opsForSet().remove(key, userId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.fail("操作失败");
        } finally {
            // 释放锁（确保是当前线程持有的锁）
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }

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
