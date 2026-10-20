package com.hmdp.infrastructure.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.hmdp.config.OssProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

@Service
@Slf4j
public class AliyunOssStorageService implements ObjectStorageService {
    @Autowired
    private OssProperties ossProperties;

    private OSS ossClient;

    /**
     * 初始化 OSS 客户端
     */
    @PostConstruct
    public void init() {
        this.ossClient = new OSSClientBuilder().build(
                ossProperties.getEndpoint(),
                ossProperties.getAccessKeyId(),
                ossProperties.getAccessKeySecret()
        );
        log.info("OSS 客户端初始化成功，bucketName: {}, endpoint: {}",
                ossProperties.getBucketName(), ossProperties.getEndpoint());
    }

    /**
     * 销毁 OSS 客户端
     */
    @PreDestroy
    public void destroy() {
        if (ossClient != null) {
            ossClient.shutdown();
            log.info("OSS 客户端已关闭");
        }
    }

    /**
     * 上传文件到 OSS
     *
     * @param file      上传的文件
     * @param objectKey 文件在 OSS 上的路径和名称
     * @return 上传文件的 URL
     */
    @Override
    public String upload(MultipartFile file, String objectKey) {
        // 1. 获取文件输入流
        String bucketName = ossProperties.getBucketName();
        try (InputStream inputStream = file.getInputStream()) {
            // 2. 上传文件到 OSS
            ossClient.putObject(bucketName, objectKey, inputStream);
            // 3. 构建文件的 URL
            String url = ossProperties.getEffectiveBaseUrl() + "/" + objectKey;
            log.debug("文件上传到OSS成功: {}", url);
            return url;
        } catch (IOException e) {
            // 4. 处理异常
            log.error("文件上传到OSS失败: {}", objectKey, e);
            throw new RuntimeException("文件上传失败", e);
        }
    }

    /**
     * 从 OSS 删除文件
     *
     * @param ossUrl 文件的 OSS URL
     */
    @Override
    public void delete(String ossUrl) {
        String bucketName = ossProperties.getBucketName();
        String objectKey = extractObjectKey(ossUrl);
        ossClient.deleteObject(bucketName, objectKey);
        log.debug("文件已从OSS删除: {}", ossUrl);
    }

    /**
     * 从 OSS URL 提取对象键
     *
     * @param ossUrl 文件的 OSS URL
     * @return 对象键
     */
    @Override
    public String extractObjectKey(String ossUrl) {
        String baseUrl = ossProperties.getEffectiveBaseUrl();
        if (ossUrl.startsWith(baseUrl + "/")) {
            return ossUrl.substring(baseUrl.length() + 1);
        } else if (ossUrl.startsWith(baseUrl)) {
            return ossUrl.substring(baseUrl.length());
        }
        // 如果不是以baseUrl开头，尝试作为纯路径处理
        return ossUrl.replaceAll("^/+", "");
    }
}
