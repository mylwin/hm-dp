package com.hmdp.infrastructure.cache.bloom;

import cn.hutool.json.JSONUtil;
import com.hmdp.config.ShopBloomProperties;
import com.hmdp.mapper.ShopMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jmx.JmxAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.hmdp.constants.ShopConstants.SHOP_BLOOM_MBEAN_NAME;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShopBloomManagementTest {
    private ShopBloomInitializer initializer;
    private ShopBloomFilter bloom;
    private ShopMapper mapper;
    private MBeanServer server;

    /**
     * 使用真实本地 MBeanServer 和模拟构建依赖准备维护入口测试。
     *
     * @throws Exception 配置模拟锁调用时发生异常
     */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        bloom = mock(ShopBloomFilter.class);
        mapper = mock(ShopMapper.class);
        RLock lock = mock(RLock.class);
        RBloomFilter<Long> candidate = mock(RBloomFilter.class);
        when(bloom.initializationLock()).thenReturn(lock);
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(bloom.newCandidate()).thenReturn(candidate);
        when(candidate.tryInit(100_000, 0.001)).thenReturn(true);
        when(candidate.getName()).thenReturn("manual-candidate");
        AtomicReference<String> last = new AtomicReference<>();
        doAnswer(call -> { last.set(call.getArgument(0)); return null; }).when(bloom).recordBuildStatus(anyString());
        when(bloom.lastBuildStatus()).thenAnswer(call -> last.get());
        initializer = new ShopBloomInitializer(new ShopBloomProperties(), bloom, mapper);
        server = MBeanServerFactory.newMBeanServer();
    }

    /**
     * 创建只包含 JMX 自动配置与维护 Bean 的测试上下文，不连接真实 Redis 或数据库。
     *
     * @param enabled 是否启用 Spring JMX 导出
     * @return 等待执行的隔离上下文测试器
     */
    private ApplicationContextRunner context(boolean enabled) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JmxAutoConfiguration.class))
                .withPropertyValues("spring.jmx.enabled=" + enabled)
                .withBean("mbeanServer", MBeanServer.class, () -> server)
                .withBean("shopBloomInitializer", ShopBloomInitializer.class, () -> initializer);
    }

    /** 验证关闭导出时，维护操作不会成为可调用的 MBean。 */
    @Test
    void disabledJmxDoesNotExportMaintenanceOperation() {
        context(false).run(ctx -> {
            assertNull(ctx.getStartupFailure());
            assertFalse(server.isRegistered(new ObjectName(SHOP_BLOOM_MBEAN_NAME)));
            verifyNoInteractions(mapper);
        });
    }

    /** 验证启用后可通过真实 MBean 操作重建并读取结果，普通启动方法不被导出。 */
    @Test
    void localMBeanCanRebuildAndReadStatus() {
        context(true).run(ctx -> {
            assertNull(ctx.getStartupFailure());
            ObjectName name = new ObjectName(SHOP_BLOOM_MBEAN_NAME);
            assertTrue(server.isRegistered(name));
            String before = (String) server.getAttribute(name, "LastBuildStatus");
            assertEquals("NEVER", JSONUtil.parseObj(before).getStr("status"));

            String result = (String) server.invoke(name, "rebuild", new Object[0], new String[0]);
            assertEquals("SUCCESS", JSONUtil.parseObj(result).getStr("status"));
            String after = (String) server.getAttribute(name, "LastBuildStatus");
            assertEquals("SUCCESS", JSONUtil.parseObj(after).getStr("status"));
            assertEquals("manual-candidate", JSONUtil.parseObj(after).getStr("filterName"));
            assertTrue(Arrays.stream(server.getMBeanInfo(name).getOperations())
                    .noneMatch(operation -> operation.getName().equals("run")));
            verify(mapper).selectMaxShopIdForBloom();
        });
    }
}
