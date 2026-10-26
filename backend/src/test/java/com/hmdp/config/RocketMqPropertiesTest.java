package com.hmdp.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RocketMqPropertiesTest {
    @Test
    void excludesAclCredentialsFromGeneratedToString() {
        RocketMqProperties properties = new RocketMqProperties();
        properties.setAccessKey("test-access-sensitive");
        properties.setSecretKey("test-secret-sensitive");
        assertThat(properties.toString()).doesNotContain("test-access-sensitive", "test-secret-sensitive");
    }

    @Test
    void acceptsDefaultSettingsAndMultipleNameServers() {
        RocketMqProperties properties = new RocketMqProperties();
        assertThatCode(properties::validate).doesNotThrowAnyException();
        properties.setNameServer("10.211.55.9:9876;localhost:19876");
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "10.211.55.9", "10.211.55.9:0", "10.211.55.9:65536",
            "http://10.211.55.9:9876", "10.211.55.9:9876;", "10.211.55.9:9876/path"})
    void rejectsInvalidEndpoints(String value) {
        RocketMqProperties properties = new RocketMqProperties();
        properties.setNameServer(value);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPositiveOrOverflowingTimeout() {
        RocketMqProperties properties = new RocketMqProperties();
        for (Duration duration : new Duration[]{Duration.ZERO, Duration.ofNanos(1), Duration.ofDays(30)}) {
            properties.setSendTimeout(duration);
            assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsHalfConfiguredCredentialsAndInvalidNames() {
        RocketMqProperties properties = new RocketMqProperties();
        properties.setAccessKey("test-access");
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class);
        properties.setSecretKey("test-secret");
        properties.setOrderTopic("topic with space");
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class);
        properties.setOrderTopic("hmdp-seckill-order-create");
        properties.setSendRetries(-1);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class);
    }
}
