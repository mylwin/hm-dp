package com.hmdp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.constants.PageConstants;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.security.UserContext;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 博客控制器
 *
 * @author mylwin
 * @since 2026-05-23
 */
@RestController
@RequestMapping("/blog")
public class BlogController {
    @Resource
    private IBlogService blogService;

    /**
     * 保存博客
     *
     * @param blog 博客
     * @return 博客id
     */
    @PostMapping
    public Result saveBlog(@RequestBody Blog blog) {
        return blogService.saveBlog(blog);
    }

    /**
     * 点赞
     *
     * @param id 博客id
     * @return 结果
     */
    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable("id") Long id) {
        return blogService.likeBlog(id);
    }

    /**
     * 查询我的博客
     *
     * @param current 当前页
     * @return 博客列表
     */
    @GetMapping("/of/me")
    public Result queryMyBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        // 获取登录用户
        UserDTO user = UserContext.getUser();
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", user.getId())
                .page(new Page<>(current, PageConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    /**
     * 查询热门博客
     *
     * @param current 当前页
     * @return 博客列表
     */
    @GetMapping("/hot")
    public Result queryHotBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.queryHotBlog(current);
    }

    /**
     * 根据id查询博客
     *
     * @param id 博客id
     * @return 博客
     */
    @GetMapping("/{id}")
    public Result queryBlogById(@PathVariable("id") Long id){
        return blogService.queryBlogById(id);
    }

    /**
     * 查询博客点赞的用户列表（按照时间先后顺序）
     *
     * @param id 博客id
     * @return 点赞列表
     */
    @GetMapping("/likes/{id}")
    public Result queryBlogLikes(@PathVariable("id") Long id){
        return blogService.queryBlogLikes(id);
    }

    /**
     * 查询用户博客
     *
     * @param id 用户id
     * @param current 当前页
     * @return 博客列表
     */
    @GetMapping("/of/user")
    public Result queryBlogByUserId(
            @RequestParam("id") Long id,
            @RequestParam(value = "current", defaultValue = "1") Integer current){
        return blogService.queryBlogByUserId(id, current);
    }

    /**
     * 查询关注的用户博客（滚动分页）
     *
     * @param max 最新时间戳
     * @param offset 偏移量
     * @return 博客列表
     */
    @GetMapping("/of/follow")
    public Result queryBlogOfFollow(
            @RequestParam(value = "lastId") Long max,
            @RequestParam(value = "offset", defaultValue = "0") Integer offset){
        return blogService.queryBlogOfFollow(max, offset);
    }
}
