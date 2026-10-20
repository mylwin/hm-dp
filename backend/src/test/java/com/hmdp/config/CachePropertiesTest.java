package com.hmdp.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class CachePropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CacheProperties.class)
    static class TestConfiguration {
    }

    @Test
    void usesDefaultLimit() {
        runner.run(context -> assertThat(context.getBean(CacheProperties.class)
                .getMaxConcurrentFallbacks()).isEqualTo(10));
    }

    @Test
    void bindsConfiguredLimit() {
        runner.withPropertyValues("hmdp.cache.max-concurrent-fallbacks=3")
                .run(context -> assertThat(context.getBean(CacheProperties.class)
                        .getMaxConcurrentFallbacks()).isEqualTo(3));
    }

    @Test
    void rejectsZeroLimitAtStartup() {
        assertInvalidLimit("0");
    }

    @Test
    void rejectsNegativeLimitAtStartup() {
        assertInvalidLimit("-1");
    }

    private void assertInvalidLimit(String value) {
        runner.withPropertyValues("hmdp.cache.max-concurrent-fallbacks=" + value)
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseInstanceOf(IllegalArgumentException.class));
    }
}
