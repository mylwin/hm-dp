package com.hmdp.controller;

import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.Result;
import com.hmdp.service.OssService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * 文件上传控制器
 *
 * @author mylwin
 * @since 2026-05-23
 */
@Slf4j
@RestController
@RequestMapping("upload")
public class UploadController {
    private static final long MAX_BLOG_IMAGE_SIZE = 100L * 1024 * 1024;

    @Autowired
    private OssService ossService;

    /**
     * 上传图片
     *
     * @param file 上传的文件
     * @return 文件访问路径
     */
    @PostMapping("blog")
    public Result uploadImage(@RequestParam("file") MultipartFile file) {
        // 校验文件是否为空
        if (file.isEmpty()) {
            return Result.fail("上传文件不能为空");
        }
        if (file.getSize() > MAX_BLOG_IMAGE_SIZE) {
            return Result.fail("上传文件不能超过100MB");
        }
        try {
            String originalFilename = file.getOriginalFilename();
            String objectKey = createObjectKey(originalFilename);
            String url = ossService.upload(file, objectKey);
            return Result.ok(url);
        } catch (Exception e) {
            throw new RuntimeException("文件上传失败", e);
        }
    }

    /**
     * 生成OSS对象键（散列目录 + UUID文件名）
     *
     * @param originalFilename 原始文件名（用于提取扩展名）
     * @return OSS对象键，例如 "blogs/a/3/uuid.jpg"
     */
    private String createObjectKey(String originalFilename) {
        // 1. 获取文件后缀
        String suffix = StrUtil.subAfter(originalFilename, ".", true);
        // 2. 生成唯一UUID文件名
        String name = UUID.randomUUID().toString();
        // 3. 生成散列目录
        int hash = name.hashCode();
        int d1 = hash & 0xF;
        int d2 = (hash >> 4) & 0xF;
        // 4. 组合成完整的OSS对象键
        return StrUtil.format("blogs/{}/{}/{}.{}", d1, d2, name, suffix);
    }

    /**
     * 删除文件
     *
     * @param ossUrl 文件的OSS URL
     * @return 删除结果
     */
    @GetMapping("/blog/delete")
    public Result deleteBlogImg(@RequestParam("name") String ossUrl) {
        try {
            ossService.delete(ossUrl);
            return Result.ok();
        } catch (Exception e) {
            return Result.fail("文件删除失败");
        }
    }
}
