package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 阿里云OSS属性配置类
 * @author myl
 */
@Component
@ConfigurationProperties(prefix = "aliyun.oss")
@Data
public class OssProperties {
    /**
     * OSS地域节点，例如 oss-cn-hangzhou.aliyuncs.com
     */
    private String endpoint;

    /**
     * 访问密钥ID
     */
    private String accessKeyId;

    /**
     * 访问密钥Secret
     */
    private String accessKeySecret;

    /**
     * 存储桶名称
     */
    private String bucketName;

    /**
     * 获取用于构建完整URL的基础URL
     */
    public String getEffectiveBaseUrl() {
        return String.format("https://%s.%s", bucketName, endpoint);
    }
}
