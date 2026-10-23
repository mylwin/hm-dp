package com.hmdp.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 店铺布隆过滤器属性测试
 * @author: myl
 */
class ShopBloomPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ShopBloomProperties.class)
    static class TestConfiguration { }

    /** 验证默认开启布隆组件，但保持只观察，避免升级时自动启用拦截。 */
    @Test
    void enforcementIsDisabledByDefault() {
        runner.run(context -> {
            var properties = context.getBean(ShopBloomProperties.class);
            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.isEnforce()).isFalse();
        });
    }

    /** 验证负向拦截配置可独立绑定，不改变组件启用状态。 */
    @Test
    void bindsEnforcementConfiguration() {
        runner.withPropertyValues("hmdp.cache.shop-bloom.enforce=true").run(context -> {
            var properties = context.getBean(ShopBloomProperties.class);
            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.isEnforce()).isTrue();
        });
    }

    @Test
    void bindsObservationConfiguration() {
        runner.withPropertyValues("hmdp.cache.shop-bloom.enabled=false", "hmdp.cache.shop-bloom.version=v2",
                "hmdp.cache.shop-bloom.expected-insertions=200000", "hmdp.cache.shop-bloom.false-probability=0.01",
                "hmdp.cache.shop-bloom.batch-size=1000").run(context -> {
            var properties = context.getBean(ShopBloomProperties.class);
            assertThat(properties.isEnabled()).isFalse();
            assertThat(properties.getVersion()).isEqualTo("v2");
            assertThat(properties.getExpectedInsertions()).isEqualTo(200000);
            assertThat(properties.getFalseProbability()).isEqualTo(0.01);
            assertThat(properties.getBatchSize()).isEqualTo(1000);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"expected-insertions=0", "expected-insertions=-1", "false-probability=0",
            "false-probability=1", "false-probability=NaN", "batch-size=0", "batch-size=10001", "version=bad/version"})
    void rejectsInvalidConfiguration(String property) {
        runner.withPropertyValues("hmdp.cache.shop-bloom." + property)
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseInstanceOf(IllegalArgumentException.class));
    }
}
