package com.hmdp.infrastructure.storage;

import org.springframework.web.multipart.MultipartFile;

/**
 * OSS服务接口
 */
public interface ObjectStorageService {
    /**
     * 上传文件到OSS
     * @param file 上传的文件
     * @param objectKey OSS对象键（路径），例如 "blogs/a/3/uuid.jpg"
     * @return 完整的OSS访问URL
     */
    String upload(MultipartFile file, String objectKey);

    /**
     * 从OSS删除文件
     * @param ossUrl 完整的OSS URL
     */
    void delete(String ossUrl);

    /**
     * 从完整OSS URL中提取对象键
     * @param ossUrl 完整OSS URL
     * @return 对象键，例如 "blogs/a/3/uuid.jpg"
     */
    String extractObjectKey(String ossUrl);
}
