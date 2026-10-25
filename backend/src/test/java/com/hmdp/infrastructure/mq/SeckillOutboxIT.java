package com.hmdp.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.OrderOutboxProperties;
import com.hmdp.config.SeckillOrderProperties;
import com.hmdp.config.RocketMqConfiguration;
import com.hmdp.config.RocketMqProperties;
import com.hmdp.constants.SeckillRequestStatus;
import com.hmdp.constants.SeckillReservationResult;
import com.hmdp.dto.SeckillAcceptance;
import com.hmdp.entity.MqOutbox;
import com.hmdp.exception.OrderMessagePublishException;
import com.hmdp.exception.SeckillAcceptancePendingException;
import com.hmdp.infrastructure.redis.RedisIdGenerator;
import com.hmdp.infrastructure.seckill.SeckillReservationStore;
import com.hmdp.mapper.MqOutboxMapper;
import com.hmdp.mapper.SeckillCampaignControlMapper;
import com.hmdp.mapper.SeckillRequestMapper;
import com.hmdp.mapper.SeckillMaintenanceMapper;
import com.hmdp.config.SeckillMaintenanceProperties;
import com.hmdp.service.impl.SeckillMaintenanceServiceImpl;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.service.impl.SeckillOrderServiceImpl;
import com.hmdp.service.impl.SeckillRequestServiceImpl;
import com.hmdp.service.impl.VoucherServiceImpl;
import com.hmdp.service.impl.SeckillVoucherServiceImpl;
import com.hmdp.entity.Voucher;
import com.hmdp.exception.SeckillCampaignPublicationPendingException;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.rocketmq.acl.common.AclClientRPCHook;
import org.apache.rocketmq.acl.common.SessionCredentials;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.util.StringUtils;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 显式运行的真实 MySQL 8 / Redis 集成测试；MQ 故障用替身注入，另有独立真实 Broker 用例。
 * 只创建随机前缀测试数据库和随机券编号的新命名空间，不启动旧 Stream 消费者。
 */
@EnabledIfEnvironmentVariable(named = "SECKILL_OUTBOX_IT", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SeckillOutboxIT {
    private final String database = "hmdp_mq_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private final AtomicLong sequence = new AtomicLong(1000000000000L
            + (UUID.randomUUID().getMostSignificantBits() & 0x0FFFFFFFFFFFFFFFL));
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final Set<String> redisKeys = new HashSet<>();
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private SeckillRequestMapper requests;
    private SeckillCampaignControlMapper campaigns;
    private MqOutboxMapper outbox;
    private SeckillMaintenanceMapper maintenanceMapper;
    private VoucherOrderMapper orders;
    private VoucherMapper shopVouchers;
    private SeckillVoucherMapper vouchers;
    private LettuceConnectionFactory redisFactory;
    private StringRedisTemplate redis;
    private SeckillReservationStore store;
    private RedisIdGenerator ids;
    private long voucherId;
    private String adminUrl;
    private String username;
    private String password;
    private boolean created;

    /**
     * 准备独立数据库及短超时 Redis 客户端，不加载应用配置或真实业务库。
     *
     * @throws Exception 数据库、Redis 或 Mapper 初始化失败时抛出
     */
    @BeforeAll
    void connect() throws Exception {
        String host = System.getenv().getOrDefault("MYSQL_HOST", "127.0.0.1");
        String port = System.getenv().getOrDefault("MYSQL_PORT", "3306");
        adminUrl = "jdbc:mysql://" + host + ":" + port + "/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        username = System.getenv().getOrDefault("MYSQL_USERNAME", "root");
        password = System.getenv().getOrDefault("MYSQL_PASSWORD", "123456");
        try (Connection connection = DriverManager.getConnection(adminUrl, username, password)) {
            connection.createStatement().execute("CREATE DATABASE `" + database + "`");
            created = true;
        }
        dataSource = new DriverManagerDataSource(adminUrl.replace("/?", "/" + database + "?"), username, password);
        jdbc = new JdbcTemplate(dataSource);
        ResourceDatabasePopulator migration = new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V20261024_02__seckill_request_outbox.sql"));
        migration.execute(dataSource);
        migration.execute(dataSource); // 同一脚本重复执行不得清空或破坏已有表。
        ResourceDatabasePopulator publication = new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V20261025_04__seckill_campaign_publication.sql"));
        publication.execute(dataSource);
        publication.execute(dataSource);
        ResourceDatabasePopulator maintenanceMigration = new ResourceDatabasePopulator(new ClassPathResource("db/migration/V20261025_05__seckill_maintenance.sql"));
        maintenanceMigration.execute(dataSource); maintenanceMigration.execute(dataSource);
        new ResourceDatabasePopulator(new ClassPathResource("db/seckill-order-tables.sql")).execute(dataSource);
        ResourceDatabasePopulator orderIndex = new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V20261024_03__seckill_order_unique.sql"));
        orderIndex.execute(dataSource);
        orderIndex.execute(dataSource);
        manager = new DataSourceTransactionManager(dataSource);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(SeckillRequestMapper.class);
        configuration.addMapper(SeckillCampaignControlMapper.class);
        configuration.addMapper(MqOutboxMapper.class);
        configuration.addMapper(SeckillMaintenanceMapper.class);
        configuration.addMapper(VoucherOrderMapper.class);
        configuration.addMapper(VoucherMapper.class);
        configuration.addMapper(SeckillVoucherMapper.class);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.setMapperLocations(new ClassPathResource("mapper/VoucherMapper.xml"));
        SqlSessionFactory sessions = Objects.requireNonNull(factory.getObject());
        SqlSessionTemplate template = new SqlSessionTemplate(sessions);
        requests = template.getMapper(SeckillRequestMapper.class);
        campaigns = template.getMapper(SeckillCampaignControlMapper.class);
        outbox = template.getMapper(MqOutboxMapper.class);
        maintenanceMapper = template.getMapper(SeckillMaintenanceMapper.class);
        orders = template.getMapper(VoucherOrderMapper.class);
        shopVouchers = template.getMapper(VoucherMapper.class);
        vouchers = template.getMapper(SeckillVoucherMapper.class);
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(
                System.getenv().getOrDefault("REDIS_HOST", "127.0.0.1"),
                Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379")));
        standalone.setPassword(System.getenv().getOrDefault("REDIS_PASSWORD", "20041223"));
        redisFactory = new LettuceConnectionFactory(standalone, LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(2)).shutdownTimeout(Duration.ZERO).build());
        redisFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(redisFactory);
        store = new SeckillReservationStore(redis);
        ids = mock(RedisIdGenerator.class);
        when(ids.getNextId(anyString())).thenAnswer(invocation -> sequence.incrementAndGet());
    }

    /**
     * 为每个用例创建全新的隔离活动和 10 份库存。
     */
    @BeforeEach
    void seed() {
        voucherId = 1000000000000L + (UUID.randomUUID().getMostSignificantBits() & 0x0FFFFFFFFFFFFFFFL);
        jdbc.update("INSERT INTO tb_seckill_campaign_control(voucher_id, epoch, status) VALUES (?, 1, 'READY')", voucherId);
        jdbc.update("INSERT INTO tb_seckill_voucher(voucher_id, stock) VALUES (?, 10)", voucherId);
        List<String> keys = SeckillReservationStore.keys(voucherId, 1L);
        assertThat(redis.hasKey(keys.get(0))).isFalse();
        redisKeys.addAll(keys);
        redis.opsForValue().set(keys.get(0), "10");
        redis.opsForHash().putAll(keys.get(1), Map.of("ready", "1", "epoch", "1",
                "beginAt", String.valueOf(Instant.now().minusSeconds(60).toEpochMilli()),
                "endAt", String.valueOf(Instant.now().plusSeconds(3600).toEpochMilli())));
    }

    /**
     * 回收本用例创建的键和测试表记录，不扫描或删除业务键。
     */
    @AfterEach
    void clearFixture() {
        if (jdbc != null && redis != null) {
            for (Long orderId : jdbc.queryForList("SELECT order_id FROM tb_seckill_request", Long.class)) {
                redisKeys.addAll(SeckillReservationStore.keys(voucherId, orderId));
            }
            redis.delete(redisKeys);
            redisKeys.clear();
            jdbc.update("DELETE FROM tb_seckill_maintenance_audit");
            jdbc.update("DELETE FROM tb_seckill_recovery_attempt");
            jdbc.update("DELETE FROM tb_seckill_message_quarantine");
            jdbc.update("DELETE FROM tb_mq_outbox");
            jdbc.update("DELETE FROM tb_seckill_request");
            jdbc.update("DELETE FROM tb_seckill_campaign_control");
            jdbc.update("DELETE FROM tb_voucher_order");
            jdbc.update("DELETE FROM tb_voucher");
            jdbc.update("DELETE FROM tb_seckill_voucher");
        }
    }

    /**
     * 只删除本类随机创建的测试库，关闭 Redis 客户端。
     *
     * @throws Exception 测试库清理失败时抛出
     */
    @AfterAll
    void disconnect() throws Exception {
        if (redisFactory != null) {
            redisFactory.destroy();
        }
        if (created && database.matches("hmdp_mq_it_[a-f0-9]{12}")) {
            try (Connection connection = DriverManager.getConnection(adminUrl, username, password)) {
                connection.createStatement().execute("DROP DATABASE `" + database + "`");
            }
        }
    }

    /**
     * 正常受理和重复请求只预占一次、登记一条消息，保留首次 traceId。
     */
    @Test
    void acceptsAndReusesOriginalRequest() {
        SeckillRequestServiceImpl service = service(outbox, store);
        SeckillAcceptance first = service.accept(1L, voucherId, "request-1", "trace-first");
        SeckillAcceptance retry = service.accept(1L, voucherId, "request-1", "trace-retry");
        assertThat(first.status()).isEqualTo(SeckillRequestStatus.ACCEPTED);
        assertThat(retry).isEqualTo(first);
        assertThat(stock()).isEqualTo("9");
        assertThat(count("tb_mq_outbox")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT payload FROM tb_mq_outbox", String.class)).contains("trace-first");
        assertThatThrownBy(() -> service.accept(1L, voucherId + 1, "request-1", "trace"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 新券自动发布后能直接受理，重复初始化不覆盖已预占库存。 */
    @Test
    void autoPublishesNewVoucherAndPreservesReservedStock() {
        VoucherServiceImpl publisher = voucherService(store, campaigns);
        Voucher voucher = newVoucher();
        publisher.addSeckillVoucher(voucher);
        List<String> keys = trackCampaign(voucher.getId());
        assertThat(jdbc.queryForObject("SELECT status FROM tb_seckill_campaign_control WHERE voucher_id=?",
                String.class, voucher.getId())).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT publication_status FROM tb_seckill_campaign_control WHERE voucher_id=?",
                String.class, voucher.getId())).isEqualTo("PUBLISHED");
        assertThat(redis.hasKey("hmdp:seckill:stock:" + voucher.getId())).isFalse();
        SeckillAcceptance accepted = service(outbox, store).accept(51L, voucher.getId(), "auto-publish", "trace-auto");
        redisKeys.addAll(SeckillReservationStore.keys(voucher.getId(), accepted.orderId()));
        assertThat(accepted.status()).isEqualTo(SeckillRequestStatus.ACCEPTED);
        publisher.initializeSeckillCampaign(voucher.getId());
        assertThat(redis.opsForValue().get(keys.get(0))).isEqualTo("9");
    }

    /** Redis 已执行但响应丢失时活动仍暂停，重试沿用原券及已有键完成发布。 */
    @Test
    void lostInitializationReplyCanRetryOriginalVoucher() {
        SeckillReservationStore lostReply = spy(store);
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("lost reply"); })
                .when(lostReply).initializeCampaign(any(), any(), any(), any(), any(), anyBoolean());
        Voucher voucher = newVoucher();
        assertThatThrownBy(() -> voucherService(lostReply, campaigns).addSeckillVoucher(voucher))
                .isInstanceOf(SeckillCampaignPublicationPendingException.class);
        List<String> keys = trackCampaign(voucher.getId());
        assertThat(redis.opsForValue().get(keys.get(0))).isEqualTo("10");
        assertThat(jdbc.queryForObject("SELECT status FROM tb_seckill_campaign_control WHERE voucher_id=?",
                String.class, voucher.getId())).isEqualTo("PAUSED");
        voucherService(store, campaigns).initializeSeckillCampaign(voucher.getId());
        assertThat(jdbc.queryForObject("SELECT status FROM tb_seckill_campaign_control WHERE voucher_id=?",
                String.class, voucher.getId())).isEqualTo("READY");
    }

    /** READY 更新后事务提交前失败必须回滚，Redis 已写入可供下一次核对。 */
    @Test
    void readinessRollbackDoesNotLosePendingCampaign() {
        SeckillCampaignControlMapper failing = mock(SeckillCampaignControlMapper.class, delegatesTo(campaigns));
        doAnswer(call -> {
            campaigns.markReady(call.getArgument(0), call.getArgument(1));
            throw new IllegalStateException("failure before commit");
        }).when(failing).markReady(anyLong(), anyLong());
        Voucher voucher = newVoucher();
        assertThatThrownBy(() -> voucherService(store, failing).addSeckillVoucher(voucher))
                .isInstanceOf(SeckillCampaignPublicationPendingException.class);
        trackCampaign(voucher.getId());
        assertThat(jdbc.queryForObject("SELECT publication_status FROM tb_seckill_campaign_control WHERE voucher_id=?",
                String.class, voucher.getId())).isEqualTo("PENDING");
        voucherService(store, campaigns).initializeSeckillCampaign(voucher.getId());
        assertThat(jdbc.queryForObject("SELECT status FROM tb_seckill_campaign_control WHERE voucher_id=?",
                String.class, voucher.getId())).isEqualTo("READY");
    }

    /** 已发布活动全部丢键也不得重新灌入数据库库存。 */
    @Test
    void publishedMissingKeysAreNeverRecreated() {
        VoucherServiceImpl publisher = voucherService(store, campaigns);
        Voucher voucher = newVoucher();
        publisher.addSeckillVoucher(voucher);
        List<String> keys = trackCampaign(voucher.getId());
        redis.delete(keys);
        assertThatThrownBy(() -> publisher.initializeSeckillCampaign(voucher.getId()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(redis.hasKey(keys.get(0))).isFalse();
        assertThat(redis.hasKey(keys.get(1))).isFalse();
    }

    /** 重复迁移保留新券待发布标记，历史行和已发布后暂停的活动均不接管。 */
    @Test
    void migrationPreservesPublicationOwnership() {
        SeckillReservationStore unavailable = mock(SeckillReservationStore.class);
        doThrow(new IllegalStateException("unavailable")).when(unavailable)
                .initializeCampaign(any(), any(), any(), any(), any(), anyBoolean());
        Voucher voucher = newVoucher();
        assertThatThrownBy(() -> voucherService(unavailable, campaigns).addSeckillVoucher(voucher))
                .isInstanceOf(SeckillCampaignPublicationPendingException.class);
        trackCampaign(voucher.getId());
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V20261025_04__seckill_campaign_publication.sql")).execute(dataSource);
        assertThat(jdbc.queryForObject("SELECT publication_status FROM tb_seckill_campaign_control WHERE voucher_id=?",
                String.class, voucher.getId())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT publication_status FROM tb_seckill_campaign_control WHERE voucher_id=?",
                String.class, voucherId)).isEqualTo("UNMANAGED");
        VoucherServiceImpl publisher = voucherService(store, campaigns);
        assertThatThrownBy(() -> publisher.initializeSeckillCampaign(voucherId)).isInstanceOf(IllegalStateException.class);
        publisher.initializeSeckillCampaign(voucher.getId());
        jdbc.update("UPDATE tb_seckill_campaign_control SET status='PAUSED' WHERE voucher_id=?", voucher.getId());
        assertThatThrownBy(() -> publisher.initializeSeckillCampaign(voucher.getId())).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM tb_seckill_campaign_control WHERE voucher_id=?",
                String.class, voucher.getId())).isEqualTo("PAUSED");
    }

    /** 零库存活动可正常发布，购买时返回售罄，不产生负库存。 */
    @Test
    void zeroStockCampaignPublishesAndRejectsPurchase() {
        Voucher voucher = newVoucher().setStock(0);
        voucherService(store, campaigns).addSeckillVoucher(voucher);
        List<String> keys = trackCampaign(voucher.getId());
        SeckillAcceptance accepted = service(outbox, store).accept(53L, voucher.getId(), "zero-stock", "trace-zero");
        redisKeys.addAll(SeckillReservationStore.keys(voucher.getId(), accepted.orderId()));
        assertThat(accepted.status()).isEqualTo(SeckillRequestStatus.REJECTED);
        assertThat(redis.opsForValue().get(keys.get(0))).isEqualTo("0");
    }

    /** 部分键、错误代次、已变化库存及带过期时间的键均保持原样并拒绝开放。 */
    @Test
    void conflictingInitializationDoesNotRepairOrOverwrite() {
        long candidate = sequence.incrementAndGet();
        List<String> keys = trackCampaign(candidate);
        LocalDateTime begin = LocalDateTime.now(ZoneOffset.UTC).withNano(0);
        redis.opsForValue().set(keys.get(0), "7");
        assertThatThrownBy(() -> store.initializeCampaign(candidate, 1L, 10, begin, begin.plusDays(1), true))
                .isInstanceOf(IllegalStateException.class);
        assertThat(redis.opsForValue().get(keys.get(0))).isEqualTo("7");
        assertThat(redis.hasKey(keys.get(1))).isFalse();
        redis.delete(keys);
        store.initializeCampaign(candidate, 1L, 10, begin, begin.plusDays(1), true);
        assertThatThrownBy(() -> store.initializeCampaign(candidate, 2L, 10, begin, begin.plusDays(1), true))
                .isInstanceOf(IllegalStateException.class);
        redis.opsForValue().set(keys.get(0), "9");
        assertThatThrownBy(() -> store.initializeCampaign(candidate, 1L, 10, begin, begin.plusDays(1), true))
                .isInstanceOf(IllegalStateException.class);
        assertThat(redis.opsForValue().get(keys.get(0))).isEqualTo("9");
        redis.expire(keys.get(1), Duration.ofHours(1));
        assertThatThrownBy(() -> store.initializeCampaign(candidate, 1L, 10, begin, begin.plusDays(1), false))
                .isInstanceOf(IllegalStateException.class);
    }

    /** 首次事务失败不留下半张券，也不写入任何活动 Redis 键。 */
    @Test
    void initialCreationRollsBackAllDatabaseRows() {
        SeckillCampaignControlMapper failing = mock(SeckillCampaignControlMapper.class, delegatesTo(campaigns));
        doThrow(new IllegalStateException("insert failed")).when(failing).insertPending(anyLong());
        Voucher voucher = newVoucher();
        assertThatThrownBy(() -> voucherService(store, failing).addSeckillVoucher(voucher))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(SeckillCampaignPublicationPendingException.class);
        List<String> keys = trackCampaign(voucher.getId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher WHERE id=?", Integer.class, voucher.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_seckill_voucher WHERE voucher_id=?", Integer.class, voucher.getId())).isZero();
        assertThat(redis.hasKey(keys.get(0))).isFalse();
    }

    /** 发布排他锁阻止另一线程受理和发布；NOWAIT 立即失败，不绕过锁开放。 */
    @Test
    void exclusivePublicationLockBlocksOtherWork() {
        Voucher voucher = newVoucher();
        VoucherServiceImpl publisher = voucherService(store, campaigns);
        publisher.addSeckillVoucher(voucher);
        trackCampaign(voucher.getId());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                campaigns.lockExclusive(voucher.getId());
                Future<?> competing = pool.submit(() -> {
                    assertThatThrownBy(() -> publisher.initializeSeckillCampaign(voucher.getId()))
                            .isInstanceOf(org.springframework.dao.CannotAcquireLockException.class);
                    assertThatThrownBy(() -> service(outbox, store).accept(52L, voucher.getId(), "locked-init", "trace-lock"))
                            .isInstanceOf(SeckillAcceptancePendingException.class);
                });
                try {
                    competing.get(5, TimeUnit.SECONDS);
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            });
        } finally {
            pool.shutdownNow();
        }
        publisher.initializeSeckillCampaign(voucher.getId());
    }

    /**
     * 组合真实 MyBatis-Plus 库存服务和现有发券服务，不启动完整应用及旧 Stream 线程。
     *
     * @param reservations 本用例使用的真实 Redis 组件或故障替身
     * @param controls 真实活动 Mapper 或故障替身
     * @return 使用真实数据库事务的发券服务
     */
    private VoucherServiceImpl voucherService(SeckillReservationStore reservations, SeckillCampaignControlMapper controls) {
        SeckillVoucherServiceImpl stockService = new SeckillVoucherServiceImpl();
        ReflectionTestUtils.setField(stockService, "baseMapper", vouchers);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("reservations", reservations);
        SeckillOrderProperties properties = new SeckillOrderProperties();
        properties.setReliableComponentsEnabled(true);
        VoucherServiceImpl publisher = new VoucherServiceImpl(stockService, redis, controls,
                beans.getBeanProvider(SeckillReservationStore.class), manager, properties);
        ReflectionTestUtils.setField(publisher, "baseMapper", shopVouchers);
        return publisher;
    }

    /**
     * 创建窗口覆盖当前时间的独立新券，时间精确到数据库支持的秒。
     *
     * @return 尚无编号、库存为十的新券资料
     */
    private Voucher newVoucher() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).withNano(0);
        return new Voucher().setShopId(1L).setTitle("自动发布测试券").setPayValue(5000L)
                .setActualValue(10000L).setType(1).setStock(10)
                .setBeginTime(now.minusMinutes(1)).setEndTime(now.plusHours(1));
    }

    /**
     * 登记新增活动的 Redis 键以供精确清理，不扫描业务命名空间。
     *
     * @param id 本用例创建的券编号
     * @return 库存、元数据及用户归属三个键
     */
    private List<String> trackCampaign(Long id) {
        List<String> keys = SeckillReservationStore.keys(id, 0L).subList(0, 3);
        redisKeys.addAll(keys);
        return keys;
    }

    /**
     * 不同用户并发预占不能超卖，拒绝请求不登记消息。
     *
     * @throws Exception 并发任务失败或超时时抛出
     */
    @Test
    void concurrentUsersCannotReserveBeyondStock() throws Exception {
        redis.opsForValue().set(SeckillReservationStore.keys(voucherId, 1L).get(0), "5");
        SeckillRequestServiceImpl service = service(outbox, store);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<SeckillAcceptance>> tasks = new ArrayList<>();
            for (long user = 1; user <= 20; user++) {
                long id = user;
                tasks.add(() -> service.accept(id, voucherId, "parallel-" + id, "trace"));
            }
            long accepted = 0;
            for (Future<SeckillAcceptance> future : executor.invokeAll(tasks)) {
                if (future.get(10, TimeUnit.SECONDS).status() == SeckillRequestStatus.ACCEPTED) accepted++;
            }
            assertThat(accepted).isEqualTo(5);
            assertThat(stock()).isEqualTo("0");
            assertThat(count("tb_mq_outbox")).isEqualTo(5);
            assertThat(count("tb_seckill_request")).isEqualTo(20);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 相同用户、相同请求并发，锁冲突允许重试但不能产生多个订单编号。
     *
     * @throws Exception 并发任务失败时抛出
     */
    @Test
    void concurrentSameRequestHasOneDurableOrderId() throws Exception {
        SeckillRequestServiceImpl service = service(outbox, store);
        ExecutorService executor = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Long>> tasks = new ArrayList<>();
            for (int i = 0; i < 12; i++)
                tasks.add(() -> {
                    try {
                        return service.accept(1L, voucherId, "same", "trace").orderId();
                    } catch (SeckillAcceptancePendingException ex) {
                        return ex.getOrderId();
                    }
                });
            Set<Long> orderIds = new HashSet<>();
            for (Future<Long> future : executor.invokeAll(tasks)) orderIds.add(future.get(10, TimeUnit.SECONDS));
            assertThat(orderIds).hasSize(1);
            assertThat(service.accept(1L, voucherId, "same", "trace").status()).isEqualTo(SeckillRequestStatus.ACCEPTED);
            assertThat(count("tb_seckill_request")).isEqualTo(1);
            assertThat(count("tb_mq_outbox")).isEqualTo(1);
            assertThat(stock()).isEqualTo("9");
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Outbox 写失败必须回滚 ACCEPTED，保留 PREPARING 和 Redis 预占供原请求恢复。
     */
    @Test
    void failedOutboxInsertRollsBackAcceptanceAndRetryDoesNotDeductTwice() {
        MqOutboxMapper failing = mock(MqOutboxMapper.class, delegatesTo(outbox));
        doThrow(new IllegalStateException("模拟 Outbox 写失败")).when(failing).insertEvent(any());
        assertThatThrownBy(() -> service(failing, store).accept(1L, voucherId, "rollback", "trace"))
                .isInstanceOf(SeckillAcceptancePendingException.class);
        assertThat(requests.findByRequest(1L, "rollback").getStatus()).isEqualTo(SeckillRequestStatus.PREPARING);
        assertThat(count("tb_mq_outbox")).isZero();
        assertThat(stock()).isEqualTo("9");
        assertThat(service(outbox, store).accept(1L, voucherId, "rollback", "trace").status())
                .isEqualTo(SeckillRequestStatus.ACCEPTED);
        assertThat(stock()).isEqualTo("9");
        assertThat(count("tb_mq_outbox")).isEqualTo(1);
    }

    /**
     * Lua 实际已成功但客户端超时，账本必须可通过同一 requestId 恢复。
     */
    @Test
    void redisTimeoutAfterExecutionKeepsPreparingAndResumesOriginalReservation() {
        SeckillReservationStore timeout = mock(SeckillReservationStore.class);
        when(timeout.reserve(anyLong(), anyLong(), anyLong(), anyLong())).thenAnswer(invocation -> {
            store.reserve(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2), invocation.getArgument(3));
            throw new RedisSystemException("模拟响应丢失", new TimeoutException());
        });
        assertThatThrownBy(() -> service(outbox, timeout).accept(1L, voucherId, "timeout", "trace"))
                .isInstanceOf(SeckillAcceptancePendingException.class);
        assertThat(requests.findByRequest(1L, "timeout").getStatus()).isEqualTo(SeckillRequestStatus.PREPARING);
        assertThat(stock()).isEqualTo("9");
        assertThat(service(outbox, store).accept(1L, voucherId, "timeout", "trace").status())
                .isEqualTo(SeckillRequestStatus.ACCEPTED);
        assertThat(stock()).isEqualTo("9");
    }

    /**
     * 新 requestId 也不能绕过同一用户对同一张券的预占资格。
     */
    @Test
    void sameUserDifferentRequestCannotReserveTwice() {
        SeckillRequestServiceImpl service = service(outbox, store);
        service.accept(1L, voucherId, "first", "trace");
        SeckillAcceptance duplicate = service.accept(1L, voucherId, "second", "trace");
        assertThat(duplicate.status()).isEqualTo(SeckillRequestStatus.REJECTED);
        assertThat(duplicate.reason()).isEqualTo("DUPLICATE_USER");
        assertThat(stock()).isEqualTo("9");
        assertThat(count("tb_mq_outbox")).isEqualTo(1);
    }

    /**
     * 未开始、已结束和库存不足均是已知拒绝，不消耗库存、不登记事件。
     */
    @Test
    void validatesTimeAndStockBeforeWritingReservation() {
        String meta = SeckillReservationStore.keys(voucherId, 1L).get(1);
        SeckillRequestServiceImpl service = service(outbox, store);
        redis.opsForHash().put(meta, "beginAt", String.valueOf(Instant.now().plusSeconds(30).toEpochMilli()));
        assertThat(service.accept(1L, voucherId, "early", "trace").reason()).isEqualTo("NOT_STARTED");
        redis.opsForHash().putAll(meta, Map.of("beginAt", "1", "endAt", "2"));
        assertThat(service.accept(1L, voucherId, "late", "trace").reason()).isEqualTo("ENDED");
        redis.opsForHash().put(meta, "endAt", String.valueOf(Instant.now().plusSeconds(60).toEpochMilli()));
        redis.opsForValue().set(SeckillReservationStore.keys(voucherId, 1L).get(0), "0");
        assertThat(service.accept(1L, voucherId, "empty", "trace").reason()).isEqualTo("SOLD_OUT");
        assertThat(count("tb_mq_outbox")).isZero();
        assertThat(redis.opsForHash().size(SeckillReservationStore.keys(voucherId, 1L).get(2))).isZero();
    }

    /**
     * 活动控制记录缺失时保留原请求，补齐活动后可用同一请求继续受理。
     */
    @Test
    void missingCampaignKeepsPreparingAndSameRequestResumesAfterInitialization() {
        jdbc.update("DELETE FROM tb_seckill_campaign_control WHERE voucher_id = ?", voucherId);
        SeckillRequestServiceImpl service = service(outbox, store);

        assertThatThrownBy(() -> service.accept(1L, voucherId, "missing-campaign", "trace"))
                .isInstanceOf(SeckillAcceptancePendingException.class)
                .hasRootCauseMessage("活动控制未就绪，保留请求等待恢复");
        var preparing = requests.findByRequest(1L, "missing-campaign");
        assertThat(preparing.getStatus()).isEqualTo(SeckillRequestStatus.PREPARING);
        assertThat(preparing.getAcceptedAt()).isNull();
        assertThat(count("tb_mq_outbox")).isZero();
        assertThat(stock()).isEqualTo("10");
        assertThat(redis.hasKey(SeckillReservationStore.keys(voucherId, preparing.getOrderId()).get(3))).isFalse();

        jdbc.update("INSERT INTO tb_seckill_campaign_control(voucher_id, epoch, status) VALUES (?, 1, 'READY')", voucherId);
        SeckillAcceptance resumed = service.accept(1L, voucherId, "missing-campaign", "retry-trace");
        assertThat(resumed.orderId()).isEqualTo(preparing.getOrderId());
        assertThat(resumed.status()).isEqualTo(SeckillRequestStatus.ACCEPTED);
        assertThat(count("tb_seckill_request")).isEqualTo(1);
        assertThat(count("tb_mq_outbox")).isEqualTo(1);
        assertThat(stock()).isEqualTo("9");
        assertThat(service.accept(1L, voucherId, "missing-campaign", "retry-trace").orderId())
                .isEqualTo(preparing.getOrderId());
        assertThat(stock()).isEqualTo("9");
    }

    /**
     * 元数据失效或维护暂停时保留 PREPARING，不能把可能存在的预占当成已拒绝。
     */
    @Test
    void missingMetadataAndPausedCampaignDoNotDiscardUncertainRequest() {
        List<String> keys = SeckillReservationStore.keys(voucherId, 1L);
        redis.delete(keys.get(1));
        assertThatThrownBy(() -> service(outbox, store).accept(1L, voucherId, "missing", "trace"))
                .isInstanceOf(SeckillAcceptancePendingException.class);
        assertThat(requests.findByRequest(1L, "missing").getStatus()).isEqualTo(SeckillRequestStatus.PREPARING);
        jdbc.update("UPDATE tb_seckill_campaign_control SET status = 'PAUSED'");
        assertThatThrownBy(() -> service(outbox, store).accept(2L, voucherId, "paused", "trace"))
                .isInstanceOf(SeckillAcceptancePendingException.class);
        assertThat(requests.findByRequest(2L, "paused").getStatus()).isEqualTo(SeckillRequestStatus.PREPARING);
        assertThat(count("tb_mq_outbox")).isZero();
        assertThat(stock()).isEqualTo("10");
    }

    /**
     * 旧代次、错误键类型和 RELEASED 墓碑都不能触发新的扣减。
     */
    @Test
    void luaRejectsStaleEpochWrongTypeAndReleasedTombstone() {
        List<String> keys = SeckillReservationStore.keys(voucherId, 123L);
        redisKeys.addAll(keys);
        assertThat(store.reserve(voucherId, 1L, 123L, 2L)).isEqualTo(SeckillReservationResult.NOT_READY);
        redis.opsForValue().set(keys.get(2), "wrong-type");
        assertThat(store.reserve(voucherId, 1L, 123L, 1L)).isEqualTo(SeckillReservationResult.NOT_READY);
        redis.delete(keys.get(2));
        redis.opsForHash().putAll(keys.get(3), Map.of("state", "RELEASED", "userId", "1", "voucherId", "" + voucherId));
        assertThat(store.reserve(voucherId, 1L, 123L, 1L)).isEqualTo(SeckillReservationResult.TERMINAL);
        assertThat(stock()).isEqualTo("10");
    }

    /**
     * 首次拒绝写入终态，即使库存后来恢复，迟到的同订单命令也不能重新预占。
     */
    @Test
    void rejectedOrderCannotReserveAgainAfterStockReturns() {
        List<String> keys = SeckillReservationStore.keys(voucherId, 789L);
        redisKeys.addAll(keys);
        redis.opsForValue().set(keys.get(0), "0");
        assertThat(store.reserve(voucherId, 1L, 789L, 1L)).isEqualTo(SeckillReservationResult.SOLD_OUT);
        assertThat(redis.opsForHash().get(keys.get(3), "state")).isEqualTo("REJECTED");
        redis.opsForValue().set(keys.get(0), "2");
        assertThat(store.reserve(voucherId, 1L, 789L, 1L)).isEqualTo(SeckillReservationResult.SOLD_OUT);
        assertThat(stock()).isEqualTo("2");
        assertThat(redis.opsForHash().size(keys.get(2))).isZero();
    }

    /**
     * MQ 超时保留原消息体和预占，之后成功只更新 SENT，发送发生在数据库事务外。
     *
     * @throws Exception 读取消息 JSON 失败时抛出
     */
    @Test
    void outboxRetriesWithoutCompensationAndSendsOutsideTransaction() throws Exception {
        service(outbox, store).accept(1L, voucherId, "send", "trace");
        String payload = jdbc.queryForObject("SELECT payload FROM tb_mq_outbox", String.class);
        SeckillOrderMessage expected = json.readValue(payload, SeckillOrderMessage.class);
        RocketMqOrderPublisher publisher = mock(RocketMqOrderPublisher.class);
        when(publisher.publish(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            throw new OrderMessagePublishException("模拟 Broker 结果未知");
        });
        OrderOutboxDispatcher dispatcher = dispatcher(outbox, publisher);
        assertThat(dispatcher.dispatchDue()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM tb_mq_outbox", String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT next_retry_at > UTC_TIMESTAMP(3) FROM tb_mq_outbox", Boolean.class)).isTrue();
        assertThat(stock()).isEqualTo("9");
        assertThat(requests.findByRequest(1L, "send").getStatus()).isEqualTo(SeckillRequestStatus.ACCEPTED);
        jdbc.update("UPDATE tb_mq_outbox SET next_retry_at = UTC_TIMESTAMP(3)");
        doReturn(new RocketMqOrderPublisher.PublishReceipt(expected.eventId(), "broker-id")).when(publisher).publish(any());
        assertThat(dispatcher.dispatchDue()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM tb_mq_outbox", String.class)).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT payload FROM tb_mq_outbox", String.class)).isEqualTo(payload);
        verify(publisher, times(2)).publish(expected);
        assertThat(stock()).isEqualTo("9");
    }

    /**
     * 旧进程的回执和重试不能覆盖租约接管者的新状态。
     */
    @Test
    void expiredLeaseIsTakenOverAndOldTokenCannotOverwriteSent() {
        service(outbox, store).accept(1L, voucherId, "lease", "trace");
        MqOutbox event = new TransactionTemplate(manager).execute(status -> {
            MqOutbox row = outbox.lockNextDue();
            outbox.claim(row.getEventId(), "old-token", 30000);
            return row;
        });
        jdbc.update("UPDATE tb_mq_outbox SET lease_until = TIMESTAMPADD(SECOND, -1, UTC_TIMESTAMP(3))");
        RocketMqOrderPublisher publisher = mock(RocketMqOrderPublisher.class);
        when(publisher.publish(any())).thenReturn(new RocketMqOrderPublisher.PublishReceipt(event.getEventId(), "new-message"));
        assertThat(dispatcher(outbox, publisher).dispatchDue()).isEqualTo(1);
        assertThat(outbox.markSent(event.getEventId(), "old-token", "old-message")).isZero();
        assertThat(outbox.retryLater(event.getEventId(), "old-token", 1000, "OldFailure")).isZero();
        assertThat(jdbc.queryForObject("SELECT broker_message_id FROM tb_mq_outbox", String.class)).isEqualTo("new-message");
        assertThat(jdbc.queryForObject("SELECT attempts FROM tb_mq_outbox", Integer.class)).isEqualTo(2);
    }

    /**
     * 其他实例持有行锁时，SKIP LOCKED 不等待它，也不重复领取同一任务。
     *
     * @throws Exception 并发任务超时或失败时抛出
     */
    @Test
    void skipsTaskLockedByOtherInstance() throws Exception {
        service(outbox, store).accept(1L, voucherId, "locked", "trace");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> owner = executor.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                assertThat(outbox.lockNextDue()).isNotNull();
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("等待释放超时");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            RocketMqOrderPublisher publisher = mock(RocketMqOrderPublisher.class);
            assertThat(dispatcher(outbox, publisher).dispatchDue()).isZero();
            verifyNoInteractions(publisher);
            release.countDown();
            owner.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    /**
     * 模拟数据库已提交但提交响应丢失，客户端重试仍复用同一订单及唯一事件。
     */
    @Test
    void lostAcceptanceCommitResponseDoesNotCreateAnotherRequest() {
        AtomicInteger commits = new AtomicInteger();
        PlatformTransactionManager uncertain = new PlatformTransactionManager() {
            /** {@inheritDoc} */
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return manager.getTransaction(definition);
            }

            /** {@inheritDoc} */
            @Override
            public void commit(TransactionStatus status) {
                manager.commit(status);
                if (commits.incrementAndGet() == 2) throw new IllegalStateException("模拟提交响应丢失");
            }

            /** {@inheritDoc} */
            @Override
            public void rollback(TransactionStatus status) {
                manager.rollback(status);
            }
        };
        SeckillRequestServiceImpl service = new SeckillRequestServiceImpl(requests, campaigns, outbox, store,
                ids, json, uncertain, new SeckillOrderProperties());
        assertThatThrownBy(() -> service.accept(1L, voucherId, "commit-unknown", "trace"))
                .isInstanceOf(SeckillAcceptancePendingException.class);
        SeckillAcceptance retry = service(outbox, store).accept(1L, voucherId, "commit-unknown", "trace");
        assertThat(retry.status()).isEqualTo(SeckillRequestStatus.ACCEPTED);
        assertThat(count("tb_seckill_request")).isEqualTo(1);
        assertThat(count("tb_mq_outbox")).isEqualTo(1);
        assertThat(stock()).isEqualTo("9");
    }

    /**
     * Broker 已收消息但数据库回执保存失败，租约过期后重发原事件，不回补库存。
     */
    @Test
    void lostSentUpdateCanBeRecoveredByLeaseAndStableEvent() {
        SeckillAcceptance acceptance = service(outbox, store).accept(1L, voucherId, "ack-unknown", "trace");
        MqOutboxMapper failing = mock(MqOutboxMapper.class, delegatesTo(outbox));
        doThrow(new IllegalStateException("模拟数据库断开")).when(failing).markSent(anyString(), anyString(), anyString());
        doThrow(new IllegalStateException("模拟数据库断开")).when(failing).retryLater(anyString(), anyString(), anyLong(), anyString());
        RocketMqOrderPublisher publisher = mock(RocketMqOrderPublisher.class);
        when(publisher.publish(any())).thenReturn(new RocketMqOrderPublisher.PublishReceipt("order-" + acceptance.orderId(), "broker-id"));
        assertThatThrownBy(() -> dispatcher(failing, publisher).dispatchDue()).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM tb_mq_outbox", String.class)).isEqualTo("SENDING");
        jdbc.update("UPDATE tb_mq_outbox SET lease_until = TIMESTAMPADD(SECOND, -1, UTC_TIMESTAMP(3))");
        assertThat(dispatcher(outbox, publisher).dispatchDue()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM tb_mq_outbox", String.class)).isEqualTo("SENT");
        var messages = org.mockito.ArgumentCaptor.forClass(SeckillOrderMessage.class);
        verify(publisher, times(2)).publish(messages.capture());
        assertThat(messages.getAllValues().get(0)).isEqualTo(messages.getAllValues().get(1));
        assertThat(stock()).isEqualTo("9");
    }

    /**
     * 在隔离数据上串联真实 MySQL、Redis、Outbox 和虚拟机 MQ；仅测试 Topic 接收探针。
     *
     * @throws Exception 客户端连接、消息校验或接收超时失败时抛出
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "ROCKETMQ_SMOKE_TEST", matches = "true")
    void deliversAcceptedOutboxToRealBrokerOnIsolatedTopic() throws Exception {
        SeckillAcceptance accepted = service(outbox, store).accept(1L, voucherId, "real-broker", "trace-it");
        String suffix = UUID.randomUUID().toString().replace("-", "");
        RocketMqProperties properties = new RocketMqProperties();
        properties.setNameServer(System.getenv().getOrDefault("ROCKETMQ_NAMESERVER", "10.211.55.9:9876"));
        properties.setOrderTopic(System.getenv().getOrDefault("ROCKETMQ_SMOKE_TOPIC", "hmdp-order-smoke"));
        assertThat(properties.getOrderTopic()).startsWith("hmdp-order-smoke");
        properties.setProducerGroup("hmdp-outbox-it-producer-" + suffix);
        properties.setAccessKey(System.getenv("ROCKETMQ_ACCESS_KEY"));
        properties.setSecretKey(System.getenv("ROCKETMQ_SECRET_KEY"));
        DefaultMQProducer producer = new RocketMqConfiguration(properties).rocketMqProducer();
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("hmdp-outbox-it-consumer-" + suffix,
                StringUtils.hasText(properties.getAccessKey())
                        ? new AclClientRPCHook(new SessionCredentials(properties.getAccessKey(), properties.getSecretKey()))
                        : null);
        consumer.setNamesrvAddr(properties.getNameServer());
        consumer.setVipChannelEnabled(false);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.setConsumeThreadMin(1);
        consumer.setConsumeThreadMax(1);
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<String> body = new AtomicReference<>();
        consumer.subscribe(properties.getOrderTopic(), "CREATE_ORDER");
        consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
            for (var message : messages) {
                if (message.getKeys() != null && Arrays.asList(message.getKeys().split(" "))
                        .contains("order-" + accepted.orderId())) {
                    body.set(new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8));
                    received.countDown();
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        try {
            producer.start();
            RocketMqOrderPublisher publisher = new RocketMqOrderPublisher(producer, json, properties);
            assertThat(dispatcher(outbox, publisher).dispatchDue()).isEqualTo(1);
            consumer.start();
            assertThat(received.await(30, TimeUnit.SECONDS)).isTrue();
            SeckillOrderMessage persisted = json.readValue(jdbc.queryForObject("SELECT payload FROM tb_mq_outbox", String.class), SeckillOrderMessage.class);
            assertThat(json.readValue(body.get(), SeckillOrderMessage.class)).isEqualTo(persisted);
            assertThat(jdbc.queryForObject("SELECT status FROM tb_mq_outbox", String.class)).isEqualTo("SENT");
            assertThat(requests.findByRequest(1L, "real-broker").getStatus()).isEqualTo(SeckillRequestStatus.ACCEPTED);
        } finally {
            consumer.shutdown();
            producer.shutdown();
        }
    }

    /**
     * 店铺券列表必须包含上架状态，让真实前端可以判断是否允许抢购。
     */
    @Test
    void shopVoucherListIncludesSaleStatus() {
        jdbc.update("INSERT INTO tb_voucher(id,shop_id,title,pay_value,actual_value,type,status) VALUES(?,1,'接口契约测试券',100,1000,1,1)", voucherId);
        var available = shopVouchers.queryVoucherOfShop(1L);
        assertThat(available).hasSize(1);
        assertThat(available.get(0).getStatus()).isEqualTo(1);
        assertThat(available.get(0).getStock()).isEqualTo(10);
        jdbc.update("UPDATE tb_voucher SET status=2 WHERE id=?", voucherId);
        assertThat(shopVouchers.queryVoucherOfShop(1L)).isEmpty();
    }

    /**
     * 正常消费及重复消息只扣一次库存，同时保存一个确认任务。
     */
    @Test
    void createsOrderOnceAndKeepsConfirmationTask() {
        SeckillOrderServiceImpl consumer = orderService(outbox, orders);
        consumer.verifySchema();
        SeckillOrderMessage message = acceptedMessage(1L, "create-order");
        consumer.createFromMessage(message);
        consumer.createFromMessage(message);
        assertThat(jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher", Integer.class)).isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class)).isEqualTo(1);
        assertThat(requests.findByRequest(1L, "create-order").getStatus()).isEqualTo(SeckillRequestStatus.SUCCEEDED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_mq_outbox WHERE event_type='CONFIRM_RESERVATION'", Integer.class)).isEqualTo(1);
        assertThat(redis.opsForHash().get(SeckillReservationStore.keys(voucherId, message.orderId()).get(3), "state"))
                .isEqualTo("RESERVED");
    }

    /**
     * 确认任务写入失败时，数据库库存、订单和请求成功状态全部回滚。
     */
    @Test
    void confirmationFailureRollsBackStockAndOrder() {
        SeckillOrderMessage message = acceptedMessage(1L, "confirm-failure");
        MqOutboxMapper failing = mock(MqOutboxMapper.class, delegatesTo(outbox));
        doThrow(new IllegalStateException("故障注入")).when(failing).insertEvent(any());
        assertThatThrownBy(() -> orderService(failing, orders).createFromMessage(message)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher", Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class)).isZero();
        assertThat(requests.findByRequest(1L, "confirm-failure").getStatus()).isEqualTo(SeckillRequestStatus.ACCEPTED);
        orderService(outbox, orders).createFromMessage(message);
        assertThat(jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher", Integer.class)).isEqualTo(9);
    }

    /**
     * 模拟数据库已提交但客户端未收到确认，消息重投不得再次扣库。
     */
    @Test
    void lostConsumerCommitResponseIsIdempotent() {
        SeckillOrderMessage message = acceptedMessage(1L, "lost-consumer-commit");
        AtomicInteger commits = new AtomicInteger();
        PlatformTransactionManager uncertain = new PlatformTransactionManager() {
            /** {@inheritDoc} */
            @Override
            public TransactionStatus getTransaction(org.springframework.transaction.TransactionDefinition definition) {
                return manager.getTransaction(definition);
            }

            /** {@inheritDoc} */
            @Override
            public void commit(TransactionStatus status) {
                manager.commit(status);
                if (commits.incrementAndGet() == 1) throw new IllegalStateException("提交响应丢失");
            }

            /** {@inheritDoc} */
            @Override
            public void rollback(TransactionStatus status) {
                manager.rollback(status);
            }
        };
        SeckillOrderServiceImpl consumer = new SeckillOrderServiceImpl(campaigns, requests, orders, vouchers,
                outbox, json, uncertain, new SeckillOrderProperties());
        assertThatThrownBy(() -> consumer.createFromMessage(message)).isInstanceOf(IllegalStateException.class);
        orderService(outbox, orders).createFromMessage(message);
        assertThat(jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher", Integer.class)).isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class)).isEqualTo(1);
    }

    /**
     * 并发重复消费在锁冲突后重试，最终只有一张订单。
     *
     * @throws Exception 并发任务或等待失败
     */
    @Test
    void concurrentDuplicateConsumptionCreatesOneOrder() throws Exception {
        SeckillOrderMessage message = acceptedMessage(1L, "concurrent-consume");
        SeckillOrderServiceImpl consumer = orderService(outbox, orders);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) futures.add(pool.submit(() -> consumeWithRetry(consumer, message)));
            for (Future<?> future : futures) future.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher", Integer.class)).isEqualTo(9);
    }

    /**
     * 数据库只有三份库存时，六个已受理请求最多创建三单，其余登记释放任务。
     *
     * @throws Exception 并发执行失败
     */
    @Test
    void databaseStockDoesNotGoNegativeUnderContention() throws Exception {
        List<SeckillOrderMessage> messages = new ArrayList<>();
        for (long user = 1; user <= 6; user++) messages.add(acceptedMessage(user, "stock-" + user));
        jdbc.update("UPDATE tb_seckill_voucher SET stock=3");
        SeckillOrderServiceImpl consumer = orderService(outbox, orders);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (SeckillOrderMessage message : messages)
                futures.add(pool.submit(() -> consumeWithRetry(consumer, message)));
            for (Future<?> future : futures) future.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_seckill_request WHERE status='CLOSED'", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_mq_outbox WHERE event_type='RELEASE_RESERVATION'", Integer.class)).isEqualTo(3);
    }

    /**
     * 两笔同用户同券请求绕过应用查重竞争唯一索引，失败方的扣库必须回滚。
     *
     * @throws Exception 并发执行或屏障等待失败
     */
    @Test
    void uniqueConflictRollsBackLosingStockDeduction() throws Exception {
        SeckillOrderMessage first = acceptedMessage(1L, "unique-first");
        redis.opsForHash().delete(SeckillReservationStore.keys(voucherId, first.orderId()).get(2), "1");
        SeckillOrderMessage second = acceptedMessage(1L, "unique-second");
        VoucherOrderMapper racing = mock(VoucherOrderMapper.class, delegatesTo(orders));
        CountDownLatch checked = new CountDownLatch(2);
        AtomicInteger checks = new AtomicInteger();
        doAnswer(invocation -> {
            if (checks.incrementAndGet() <= 2) {
                checked.countDown();
                if (!checked.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("查重屏障超时");
                return null;
            }
            return orders.findUserVoucherOrder(invocation.getArgument(0), invocation.getArgument(1));
        }).when(racing).findUserVoucherOrder(anyLong(), anyLong());
        SeckillOrderServiceImpl consumer = orderService(outbox, racing);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> one = pool.submit(() -> consumeWithRetry(consumer, first));
            Future<?> two = pool.submit(() -> consumeWithRetry(consumer, second));
            one.get(15, TimeUnit.SECONDS);
            two.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher", Integer.class)).isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_mq_outbox WHERE event_type='RELEASE_RESERVATION'", Integer.class)).isEqualTo(1);
    }

    /**
     * 有历史重复订单时索引迁移失败并保留数据，不静默清理。
     */
    @Test
    void uniqueMigrationRefusesHistoricalDuplicates() {
        jdbc.execute("ALTER TABLE tb_voucher_order DROP INDEX uk_order_user_voucher");
        try {
            jdbc.update("INSERT INTO tb_voucher_order(id,user_id,voucher_id) VALUES(1,1,?),(2,1,?)", voucherId, voucherId);
            ResourceDatabasePopulator migration = new ResourceDatabasePopulator(new ClassPathResource(
                    "db/migration/V20261024_03__seckill_order_unique.sql"));
            assertThatThrownBy(() -> migration.execute(dataSource)).isInstanceOf(org.springframework.jdbc.datasource.init.ScriptException.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class)).isEqualTo(2);
            assertThat(orders.countUserVoucherUniqueIndexes()).isZero();
        } finally {
            jdbc.update("DELETE FROM tb_voucher_order");
            new ResourceDatabasePopulator(new ClassPathResource(
                    "db/migration/V20261024_03__seckill_order_unique.sql")).execute(dataSource);
        }
    }

    /**
     * 释放任务写失败时关闭状态也回滚，不能丢失后续库存处理依据。
     */
    @Test
    void releaseTaskFailureKeepsRequestAccepted() {
        SeckillOrderMessage message = acceptedMessage(1L, "release-failure");
        jdbc.update("UPDATE tb_seckill_voucher SET stock=0");
        MqOutboxMapper failing = mock(MqOutboxMapper.class, delegatesTo(outbox));
        doThrow(new IllegalStateException("释放任务写入失败")).when(failing).insertEvent(any());
        assertThatThrownBy(() -> orderService(failing, orders).createFromMessage(message)).isInstanceOf(IllegalStateException.class);
        assertThat(requests.findByRequest(1L, "release-failure").getStatus()).isEqualTo(SeckillRequestStatus.ACCEPTED);
        orderService(outbox, orders).createFromMessage(message);
        assertThat(requests.findByRequest(1L, "release-failure").getStatus()).isEqualTo(SeckillRequestStatus.CLOSED);
        assertThat(service(outbox, store).queryByRequest(1L, "release-failure").reasonDescription()).isEqualTo("数据库库存不足");
    }

    /**
     * 确认和释放任务不会被 CREATE_ORDER 投递器误发到 MQ。
     */
    @Test
    void mqDispatcherLeavesRedisTasksPending() {
        SeckillOrderMessage message = acceptedMessage(1L, "task-routing");
        orderService(outbox, orders).createFromMessage(message);
        jdbc.update("UPDATE tb_mq_outbox SET status='SENT' WHERE event_type='CREATE_ORDER'");
        RocketMqOrderPublisher publisher = mock(RocketMqOrderPublisher.class);
        assertThat(dispatcher(outbox, publisher).dispatchDue()).isZero();
        verifyNoInteractions(publisher);
        assertThat(jdbc.queryForObject("SELECT status FROM tb_mq_outbox WHERE event_type='CONFIRM_RESERVATION'", String.class)).isEqualTo("PENDING");
    }

    /**
     * 结果查询按用户隔离，返回字符串编号和中文拒绝原因。
     */
    @Test
    void requestQueriesAreOwnedAndDescribeReasons() {
        SeckillRequestServiceImpl acceptance = service(outbox, store);
        SeckillOrderMessage first = acceptedMessage(1L, "owned-query");
        assertThat(acceptance.queryByOrder(2L, first.orderId())).isNull();
        assertThat(acceptance.queryByRequest(2L, "owned-query")).isNull();
        assertThat(acceptance.queryByOrder(1L, first.orderId()).orderId()).isEqualTo(first.orderId().toString());
        acceptance.accept(1L, voucherId, "duplicate-query", "trace");
        assertThat(acceptance.queryByRequest(1L, "duplicate-query").reasonDescription()).isEqualTo("重复购买或预占");
    }

    /**
     * PREPARING、维护中活动和身份不符的消息都不能建单。
     */
    @Test
    void unfinishedAndMismatchedRequestsCannotCreateOrders() {
        SeckillOrderMessage message = acceptedMessage(1L, "gates");
        SeckillOrderServiceImpl consumer = orderService(outbox, orders);
        jdbc.update("UPDATE tb_seckill_request SET status='PREPARING'");
        assertThatThrownBy(() -> consumer.createFromMessage(message)).isInstanceOf(IllegalStateException.class);
        jdbc.update("UPDATE tb_seckill_request SET status='ACCEPTED'");
        jdbc.update("UPDATE tb_seckill_campaign_control SET status='PAUSED'");
        assertThatThrownBy(() -> consumer.createFromMessage(message)).isInstanceOf(IllegalStateException.class);
        jdbc.update("UPDATE tb_seckill_campaign_control SET status='READY'");
        SeckillOrderMessage forged = new SeckillOrderMessage(1, message.eventId(), message.orderId(), 2L,
                message.voucherId(), message.traceId(), message.acceptedAt());
        assertThatThrownBy(() -> consumer.createFromMessage(forged)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher", Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class)).isZero();
    }

    /**
     * 真实 Broker 向正式监听器重复投递，MQ 确认前必须已提交订单。
     *
     * @throws Exception MQ 客户端或消息等待失败
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "ROCKETMQ_SMOKE_TEST", matches = "true")
    void realBrokerCreatesOrderThroughProductionListener() throws Exception {
        SeckillOrderMessage message = acceptedMessage(1L, "real-consumer");
        String suffix = UUID.randomUUID().toString().replace("-", "");
        RocketMqProperties properties = new RocketMqProperties();
        properties.setNameServer(System.getenv().getOrDefault("ROCKETMQ_NAMESERVER", "10.211.55.9:9876"));
        properties.setOrderTopic(System.getenv().getOrDefault("ROCKETMQ_SMOKE_TOPIC", "hmdp-order-smoke"));
        assertThat(properties.getOrderTopic()).startsWith("hmdp-order-smoke");
        properties.setProducerGroup("hmdp-phase3-producer-" + suffix);
        properties.setAccessKey(System.getenv("ROCKETMQ_ACCESS_KEY"));
        properties.setSecretKey(System.getenv("ROCKETMQ_SECRET_KEY"));
        RocketMqConfiguration configuration = new RocketMqConfiguration(properties);
        DefaultMQProducer producer = configuration.rocketMqProducer();
        SeckillOrderServiceImpl business = orderService(outbox, orders);
        RocketMqOrderListener listener = new RocketMqOrderListener(json, business, new OrderMessageFailureStore(maintenanceMapper, manager));
        com.hmdp.config.OrderConsumerProperties consumerProperties = new com.hmdp.config.OrderConsumerProperties();
        consumerProperties.setGroup("hmdp-phase3-consumer-" + suffix);
        consumerProperties.setThreads(1);
        DefaultMQPushConsumer consumer = configuration.rocketMqOrderConsumer(consumerProperties, listener, business);
        CountDownLatch processed = new CountDownLatch(2);
        consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
            // 探针组忽略其他测试的历史事件，只有本用例事件进入正式业务监听器。
            for (var raw : messages) {
                if (raw.getKeys() != null && Arrays.asList(raw.getKeys().split(" ")).contains(message.eventId())) {
                    ConsumeConcurrentlyStatus status = listener.consumeMessage(List.of(raw), context);
                    if (status != ConsumeConcurrentlyStatus.CONSUME_SUCCESS) return status;
                    assertThat(jdbc.queryForObject("SELECT status FROM tb_seckill_request", String.class)).isEqualTo("SUCCEEDED");
                    processed.countDown();
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        try {
            producer.start();
            consumer.start();
            RocketMqOrderPublisher publisher = new RocketMqOrderPublisher(producer, json, properties);
            assertThat(dispatcher(outbox, publisher).dispatchDue()).isEqualTo(1);
            publisher.publish(message);
            assertThat(processed.await(40, TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher", Integer.class)).isEqualTo(9);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class)).isEqualTo(1);
        } finally {
            consumer.shutdown();
            producer.shutdown();
        }
    }

    /** 成功订单确认任务只改变预占状态，重复执行不重复减库存。 */
    @Test
    void confirmationTaskIsIdempotent() {
        SeckillOrderMessage message = acceptedMessage(1L, "confirm-once");
        orderService(outbox, orders).createFromMessage(message);
        SeckillMaintenanceServiceImpl worker = maintenanceService(store, outbox);
        assertThat(worker.processReservationTasks()).isEqualTo(1);
        assertThat(redis.opsForHash().get(SeckillReservationStore.keys(voucherId, message.orderId()).get(3), "state")).isEqualTo("CONFIRMED");
        jdbc.update("UPDATE tb_mq_outbox SET status='PENDING' WHERE event_type='CONFIRM_RESERVATION'");
        worker.processReservationTasks();
        assertThat(stock()).isEqualTo("9");
        assertThat(jdbc.queryForObject("SELECT status FROM tb_mq_outbox WHERE event_type='CONFIRM_RESERVATION'", String.class)).isEqualTo("SENT");
        assertThatThrownBy(() -> worker.close(message.orderId(), "operator-1", "不能关闭成功订单")).isInstanceOf(IllegalStateException.class);
    }

    /** 关闭请求先提交释放任务，执行器最多回补一次，旧释放不删除新订单归属。 */
    @Test
    void releaseDoesNotDeleteNewPurchaseOwnership() {
        SeckillOrderMessage message = acceptedMessage(1L, "release-once");
        SeckillMaintenanceServiceImpl worker = maintenanceService(store, outbox);
        worker.close(message.orderId(), "operator-1", "隔离测试人工关闭");
        assertThat(stock()).isEqualTo("9");
        worker.processReservationTasks();
        assertThat(stock()).isEqualTo("10");
        SeckillAcceptance renewed = service(outbox, store).accept(1L, voucherId, "renewed", "trace-renewed");
        jdbc.update("UPDATE tb_mq_outbox SET status='PENDING' WHERE event_type='RELEASE_RESERVATION'");
        worker.processReservationTasks();
        assertThat(stock()).isEqualTo("9");
        assertThat(redis.opsForHash().get(SeckillReservationStore.keys(voucherId, message.orderId()).get(2), "1")).isEqualTo(renewed.orderId().toString());
        orderService(outbox, orders).createFromMessage(message);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class)).isZero();
    }

    /** 关闭尚未执行 Redis 的 PREPARING 后写墓碑，迟到预占不得扣库存。 */
    @Test
    void preparingClosureFencesLateReservation() {
        SeckillReservationStore unavailable = mock(SeckillReservationStore.class);
        doThrow(new IllegalStateException("timeout")).when(unavailable).reserve(any(), any(), any(), any());
        assertThatThrownBy(() -> service(outbox, unavailable).accept(1L, voucherId, "close-preparing", "trace"))
                .isInstanceOf(SeckillAcceptancePendingException.class);
        Long id = requests.findByRequest(1L, "close-preparing").getOrderId();
        SeckillMaintenanceServiceImpl worker = maintenanceService(store, outbox);
        worker.close(id, "operator-1", "核对后明确取消尚未受理请求");
        worker.processReservationTasks();
        assertThat(store.reserve(voucherId, 1L, id, 1L)).isEqualTo(SeckillReservationResult.TERMINAL);
        assertThat(stock()).isEqualTo("10");
    }

    /** Redis 释放成功但响应丢失时，数据库保留任务；下一轮只确认原结果，不再次回补。 */
    @Test
    void lostReleaseReplyRetriesWithoutDoubleIncrement() {
        SeckillOrderMessage message = acceptedMessage(1L, "lost-release");
        maintenanceService(store, outbox).close(message.orderId(), "operator-1", "释放响应故障测试");
        SeckillReservationStore lostReply = spy(store);
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("lost reply"); }).when(lostReply)
                .finish(any(), any(), any(), any(), anyBoolean(), anyBoolean());
        maintenanceService(lostReply, outbox).processReservationTasks();
        assertThat(stock()).isEqualTo("10");
        assertThat(jdbc.queryForObject("SELECT status FROM tb_mq_outbox WHERE event_type='RELEASE_RESERVATION'", String.class)).isEqualTo("PENDING");
        jdbc.update("UPDATE tb_mq_outbox SET next_retry_at=UTC_TIMESTAMP(3) WHERE event_type='RELEASE_RESERVATION'");
        maintenanceService(store, outbox).processReservationTasks();
        assertThat(stock()).isEqualTo("10");
    }

    /** 已受理请求的预占丢失不能猜测回补；任务保留并暂停活动留下审计。 */
    @Test
    void acceptedMissingReservationPausesInsteadOfGuessing() {
        SeckillOrderMessage message = acceptedMessage(1L, "missing-release");
        SeckillMaintenanceServiceImpl worker = maintenanceService(store, outbox);
        worker.close(message.orderId(), "operator-1", "验证缺失预占保护");
        redis.delete(SeckillReservationStore.keys(voucherId, message.orderId()).get(3));
        worker.processReservationTasks();
        assertThat(stock()).isEqualTo("9");
        assertThat(jdbc.queryForObject("SELECT status FROM tb_seckill_campaign_control", String.class)).isEqualTo("PAUSED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_seckill_maintenance_audit WHERE action='CONSISTENCY_CONFLICT'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM tb_mq_outbox WHERE event_type='RELEASE_RESERVATION'", String.class)).isEqualTo("PENDING");
    }

    /** 成功订单的确认预占拒绝被任何释放脚本回补。 */
    @Test
    void confirmedReservationCannotBeReleased() {
        SeckillOrderMessage message = acceptedMessage(1L, "confirmed-protection");
        orderService(outbox, orders).createFromMessage(message);
        maintenanceService(store, outbox).processReservationTasks();
        assertThatThrownBy(() -> store.finish(voucherId, 1L, message.orderId(), 1L, false, true))
                .isInstanceOf(com.hmdp.exception.SeckillConsistencyException.class);
        assertThat(stock()).isEqualTo("9");
    }

    /** 错误代次和归属不得确认或回补库存。 */
    @Test
    void reservationFinishRejectsEpochAndIdentityMismatch() {
        SeckillOrderMessage message = acceptedMessage(1L, "identity-protection");
        assertThatThrownBy(() -> store.finish(voucherId, 1L, message.orderId(), 2L, false, false))
                .isInstanceOf(com.hmdp.exception.SeckillConsistencyException.class);
        assertThatThrownBy(() -> store.finish(voucherId, 2L, message.orderId(), 1L, false, false))
                .isInstanceOf(com.hmdp.exception.SeckillConsistencyException.class);
        assertThat(stock()).isEqualTo("9");
    }

    /**
     * 两个实例并发运行补偿只领取一个有效租约，库存仍只回补一次。
     * @throws Exception 并发调用失败或超时
     */
    @Test
    void concurrentMaintenanceHasOneEffectiveRelease() throws Exception {
        SeckillOrderMessage message = acceptedMessage(1L, "concurrent-release");
        SeckillMaintenanceServiceImpl worker = maintenanceService(store, outbox);
        worker.close(message.orderId(), "operator-1", "并发领取测试");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = pool.invokeAll(List.of(worker::processReservationTasks, worker::processReservationTasks));
            int total = 0;
            for (Future<Integer> result : results) {
                total += result.get(5, TimeUnit.SECONDS);
            }
            assertThat(total).isEqualTo(1);
            assertThat(stock()).isEqualTo("10");
        } finally {
            pool.shutdownNow();
        }
    }

    /** Outbox 写失败留下的 PREPARING 由后台沿用原编号恢复，不再次扣库存。 */
    @Test
    void recoveryResumesPreparingWithOriginalOrderId() {
        MqOutboxMapper failing = mock(MqOutboxMapper.class, delegatesTo(outbox));
        doThrow(new IllegalStateException("outbox unavailable")).when(failing).insertEvent(any());
        assertThatThrownBy(() -> service(failing, store).accept(1L, voucherId, "background-preparing", "trace-original"))
                .isInstanceOf(SeckillAcceptancePendingException.class);
        Long id = requests.findByRequest(1L, "background-preparing").getOrderId();
        makeRequestsStale();
        maintenanceService(store, outbox).recoverDue();
        assertThat(requests.findByRequest(1L, "background-preparing").getOrderId()).isEqualTo(id);
        assertThat(requests.findByRequest(1L, "background-preparing").getStatus()).isEqualTo(SeckillRequestStatus.ACCEPTED);
        assertThat(stock()).isEqualTo("9");
        assertThat(jdbc.queryForObject("SELECT event_id FROM tb_mq_outbox", String.class)).isEqualTo("order-" + id);
    }

    /** ACCEPTED 的 SENT 记录长期无结果时重投原消息，已有原订单也不再次扣数据库库存。 */
    @Test
    void recoveryReplaysSentMessageAndConvergesExistingOrder() {
        SeckillOrderMessage message = acceptedMessage(1L, "background-accepted");
        String original = jdbc.queryForObject("SELECT payload FROM tb_mq_outbox", String.class);
        jdbc.update("UPDATE tb_mq_outbox SET status='SENT'");
        jdbc.update("INSERT INTO tb_voucher_order(id,user_id,voucher_id) VALUES(?,?,?)", message.orderId(), 1L, voucherId);
        jdbc.update("UPDATE tb_seckill_voucher SET stock=9");
        makeRequestsStale();
        maintenanceService(store, outbox).recoverDue();
        assertThat(jdbc.queryForObject("SELECT status FROM tb_mq_outbox", String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT payload FROM tb_mq_outbox", String.class)).isEqualTo(original);
        orderService(outbox, orders).createFromMessage(message);
        maintenanceService(store, outbox).processReservationTasks();
        assertThat(jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher", Integer.class)).isEqualTo(9);
        assertThat(stock()).isEqualTo("9");
    }

    /** ACCEPTED 缺 Outbox 可从账本补回同一事件，不重新生成业务编号。 */
    @Test
    void recoveryRecreatesMissingOutboxFromLedger() {
        SeckillOrderMessage message = acceptedMessage(1L, "missing-event");
        jdbc.update("DELETE FROM tb_mq_outbox");
        makeRequestsStale();
        maintenanceService(store, outbox).recoverDue();
        assertThat(jdbc.queryForObject("SELECT event_id FROM tb_mq_outbox", String.class)).isEqualTo(message.eventId());
        assertThat(stock()).isEqualTo("9");
    }

    /** 数据冲突暂停活动并保留 ACCEPTED，不以超时关闭或盲目释放。 */
    @Test
    void recoveryConflictPausesAndPreservesAcceptedRequest() {
        SeckillOrderMessage message = acceptedMessage(1L, "recovery-conflict");
        redis.opsForHash().put(SeckillReservationStore.keys(voucherId, message.orderId()).get(3), "epoch", "2");
        makeRequestsStale();
        maintenanceService(store, outbox).recoverDue();
        assertThat(requests.findByRequest(1L, "recovery-conflict").getStatus()).isEqualTo(SeckillRequestStatus.ACCEPTED);
        assertThat(jdbc.queryForObject("SELECT status FROM tb_seckill_campaign_control", String.class)).isEqualTo("PAUSED");
        assertThat(stock()).isEqualTo("9");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_seckill_recovery_attempt WHERE last_error IS NOT NULL", Integer.class)).isEqualTo(1);
    }

    /** 后台恢复可继续自动发布已落库但从未准备 Redis 的新券。 */
    @Test
    void recoveryPublishesPendingCampaign() {
        SeckillReservationStore unavailable = mock(SeckillReservationStore.class);
        doThrow(new IllegalStateException("offline")).when(unavailable).initializeCampaign(any(), any(), any(), any(), any(), anyBoolean());
        Voucher voucher = newVoucher();
        assertThatThrownBy(() -> voucherService(unavailable, campaigns).addSeckillVoucher(voucher))
                .isInstanceOf(SeckillCampaignPublicationPendingException.class);
        trackCampaign(voucher.getId());
        maintenanceService(store, outbox).recoverDue();
        assertThat(jdbc.queryForObject("SELECT status FROM tb_seckill_campaign_control WHERE voucher_id=?", String.class, voucher.getId())).isEqualTo("READY");
    }

    /** 有效发送租约不能被人工重投抢占，失败操作不留下成功审计。 */
    @Test
    void controlledReplayDoesNotStealSendingLease() {
        SeckillOrderMessage message = acceptedMessage(1L, "live-lease");
        jdbc.update("UPDATE tb_mq_outbox SET status='SENDING',lease_token='active',lease_until=TIMESTAMPADD(SECOND,60,UTC_TIMESTAMP(3))");
        assertThatThrownBy(() -> maintenanceService(store, outbox).replay(message.orderId(), null, "operator-1", "不能抢租约"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT lease_token FROM tb_mq_outbox", String.class)).isEqualTo("active");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_seckill_maintenance_audit", Integer.class)).isZero();
    }

    /**
     * 死信先可靠隔离，重投按原账本核对且同事务保留处置审计。
     * @throws Exception 测试消息序列化失败
     */
    @Test
    void deadLetterQuarantineAndReplayAreDurable() throws Exception {
        SeckillOrderMessage message = acceptedMessage(1L, "dead-letter");
        org.apache.rocketmq.common.message.MessageExt raw = new org.apache.rocketmq.common.message.MessageExt();
        raw.setBody(json.writeValueAsBytes(message)); raw.setMsgId("test-dlq-1"); raw.setTopic("%DLQ%test-cg");
        OrderMessageFailureStore failures = new OrderMessageFailureStore(maintenanceMapper, manager);
        RocketMqDeadLetterListener listener = new RocketMqDeadLetterListener(failures);
        assertThat(listener.consumeMessage(List.of(raw), null)).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        assertThat(listener.consumeMessage(List.of(raw), null)).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_seckill_message_quarantine", Integer.class)).isEqualTo(1);
        String isolated = jdbc.queryForObject("SELECT quarantine_id FROM tb_seckill_message_quarantine", String.class);
        jdbc.update("UPDATE tb_mq_outbox SET status='SENT'");
        maintenanceService(store, outbox).replay(message.orderId(), isolated, "operator-1", "核对死信后重投原订单");
        assertThat(jdbc.queryForObject("SELECT resolved FROM tb_seckill_message_quarantine", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM tb_mq_outbox", String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_seckill_maintenance_audit WHERE action='REPLAY'", Integer.class)).isEqualTo(1);
        assertThat(stock()).isEqualTo("9");
    }

    /** 隔离原文与请求不一致时，重投和隔离处置一起回滚，不能替换 payload。 */
    @Test
    void mismatchedQuarantineRollsBackReplay() {
        SeckillOrderMessage message = acceptedMessage(1L, "bad-quarantine");
        org.apache.rocketmq.common.message.MessageExt raw = new org.apache.rocketmq.common.message.MessageExt();
        raw.setBody("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)); raw.setMsgId("bad-body"); raw.setTopic("test");
        new OrderMessageFailureStore(maintenanceMapper, manager).record(raw, "INVALID", "INVALID_JSON");
        jdbc.update("UPDATE tb_mq_outbox SET status='SENT'");
        String isolated = jdbc.queryForObject("SELECT quarantine_id FROM tb_seckill_message_quarantine", String.class);
        assertThatThrownBy(() -> maintenanceService(store, outbox).replay(message.orderId(), isolated, "operator-1", "错误原文不得重投"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM tb_mq_outbox", String.class)).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT resolved FROM tb_seckill_message_quarantine", Boolean.class)).isFalse();
    }

    /** 健康统计和隔离摘要不暴露原文，人工处置只关闭待办并保留审计。 */
    @Test
    void healthAndDismissKeepPayloadPrivate() {
        acceptedMessage(1L, "health");
        org.apache.rocketmq.common.message.MessageExt raw = new org.apache.rocketmq.common.message.MessageExt();
        raw.setBody("invalid".getBytes(java.nio.charset.StandardCharsets.UTF_8)); raw.setMsgId("health"); raw.setTopic("test");
        new OrderMessageFailureStore(maintenanceMapper, manager).record(raw, "INVALID", "BAD_BODY");
        SeckillMaintenanceServiceImpl worker = maintenanceService(store, outbox);
        assertThat(((Number) worker.health().get("pending_tasks")).intValue()).isEqualTo(1);
        assertThat(((Number) worker.health().get("quarantine_pending")).intValue()).isEqualTo(1);
        assertThat(worker.quarantinedMessages().get(0)).doesNotContainKey("body");
        String id = jdbc.queryForObject("SELECT quarantine_id FROM tb_seckill_message_quarantine", String.class);
        worker.dismissQuarantine(id, "operator-1", "确认无效消息无需建单");
        assertThat(((Number) worker.health().get("quarantine_pending")).intValue()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_seckill_message_quarantine", Integer.class)).isEqualTo(1);
    }

    /** 恢复失败会持久化退避，下轮立即扫描不反复占用同一请求。 */
    @Test
    void failedRecoveryHasDurableBackoff() {
        SeckillOrderMessage message = acceptedMessage(1L, "recovery-backoff");
        redis.delete(SeckillReservationStore.keys(voucherId, message.orderId()).get(3));
        makeRequestsStale();
        SeckillMaintenanceServiceImpl worker = maintenanceService(store, outbox);
        worker.recoverDue();
        assertThat(worker.recoverDue()).isZero();
        assertThat(jdbc.queryForObject("SELECT attempts FROM tb_seckill_recovery_attempt", Integer.class)).isEqualTo(1);
    }

    /** 首次发布的数据冲突被标成 BLOCKED，后台下一轮不重新开放。 */
    @Test
    void conflictingPendingPublicationRemainsBlocked() {
        SeckillReservationStore unavailable = mock(SeckillReservationStore.class);
        doThrow(new IllegalStateException("offline")).when(unavailable).initializeCampaign(any(), any(), any(), any(), any(), anyBoolean());
        Voucher voucher = newVoucher();
        assertThatThrownBy(() -> voucherService(unavailable, campaigns).addSeckillVoucher(voucher))
                .isInstanceOf(SeckillCampaignPublicationPendingException.class);
        List<String> keys = trackCampaign(voucher.getId());
        redis.opsForValue().set(keys.get(0), "7");
        SeckillMaintenanceServiceImpl worker = maintenanceService(store, outbox);
        worker.recoverDue();
        assertThat(jdbc.queryForObject("SELECT publication_status FROM tb_seckill_campaign_control WHERE voucher_id=?", String.class, voucher.getId())).isEqualTo("BLOCKED");
        assertThat(worker.recoverDue()).isZero();
        assertThat(redis.opsForValue().get(keys.get(0))).isEqualTo("7");
    }

    /** 已发布旧代次任务不会操作新代次库存，也不会暂停新的活动代次。 */
    @Test
    void oldEpochTaskCannotPauseOrMutateNewEpoch() {
        SeckillOrderMessage message = acceptedMessage(1L, "old-epoch");
        SeckillMaintenanceServiceImpl worker = maintenanceService(store, outbox);
        worker.close(message.orderId(), "operator-1", "旧代次任务保护");
        jdbc.update("UPDATE tb_seckill_campaign_control SET epoch=2");
        redis.opsForHash().put(SeckillReservationStore.keys(voucherId, message.orderId()).get(1), "epoch", "2");
        worker.processReservationTasks();
        assertThat(stock()).isEqualTo("9");
        assertThat(jdbc.queryForObject("SELECT status FROM tb_seckill_campaign_control", String.class)).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT status FROM tb_mq_outbox WHERE event_type='RELEASE_RESERVATION'", String.class)).isEqualTo("PENDING");
    }

    /** Redis 确认成功但响应丢失后再次执行只核对确认，不再次扣库存。 */
    @Test
    void lostConfirmationReplyConvergesWithoutDeduction() {
        SeckillOrderMessage message = acceptedMessage(1L, "lost-confirm");
        orderService(outbox, orders).createFromMessage(message);
        SeckillReservationStore lostReply = spy(store);
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("lost reply"); }).when(lostReply)
                .finish(any(), any(), any(), any(), anyBoolean(), anyBoolean());
        maintenanceService(lostReply, outbox).processReservationTasks();
        jdbc.update("UPDATE tb_mq_outbox SET next_retry_at=UTC_TIMESTAMP(3) WHERE event_type='CONFIRM_RESERVATION'");
        maintenanceService(store, outbox).processReservationTasks();
        assertThat(stock()).isEqualTo("9");
        assertThat(jdbc.queryForObject("SELECT status FROM tb_mq_outbox WHERE event_type='CONFIRM_RESERVATION'", String.class)).isEqualTo("SENT");
    }

    /** 隔离消息超过原文上限只存摘要，避免读取接口或数据库被超大消息占满。 */
    @Test
    void oversizedMessageQuarantineKeepsDigestOnly() {
        org.apache.rocketmq.common.message.MessageExt raw = new org.apache.rocketmq.common.message.MessageExt();
        raw.setBody(new byte[65537]); raw.setTopic("isolated-test"); raw.setMsgId("oversized");
        new OrderMessageFailureStore(maintenanceMapper, manager).record(raw, "INVALID", "BODY_TOO_LARGE");
        assertThat(jdbc.queryForObject("SELECT body FROM tb_seckill_message_quarantine", byte[].class)).isNull();
        assertThat(jdbc.queryForObject("SELECT body_hash FROM tb_seckill_message_quarantine", String.class)).hasSize(64);
    }

    /** Redis 已释放但数据库回执事务回滚，任务仍能重试且不会重复回补。 */
    @Test
    void reservationReceiptRollbackIsRecoverable() {
        SeckillOrderMessage message = acceptedMessage(1L, "receipt-rollback");
        maintenanceService(store, outbox).close(message.orderId(), "operator-1", "回执事务回滚验证");
        MqOutboxMapper failing = mock(MqOutboxMapper.class, delegatesTo(outbox));
        doAnswer(call -> {
            outbox.markSent(call.getArgument(0), call.getArgument(1), call.getArgument(2));
            throw new IllegalStateException("receipt commit failed");
        }).when(failing).markSent(anyString(), anyString(), anyString());
        maintenanceService(store, failing).processReservationTasks();
        assertThat(stock()).isEqualTo("10");
        assertThat(jdbc.queryForObject("SELECT status FROM tb_mq_outbox WHERE event_type='RELEASE_RESERVATION'", String.class)).isEqualTo("PENDING");
        jdbc.update("UPDATE tb_mq_outbox SET next_retry_at=UTC_TIMESTAMP(3) WHERE event_type='RELEASE_RESERVATION'");
        maintenanceService(store, outbox).processReservationTasks();
        assertThat(stock()).isEqualTo("10");
    }

    /** 释放任务写入失败必须连同关闭状态一起回滚，不能丢失回补任务。 */
    @Test
    void closureAndReleaseTaskAreAtomic() {
        SeckillOrderMessage message = acceptedMessage(1L, "close-rollback");
        MqOutboxMapper failing = mock(MqOutboxMapper.class, delegatesTo(outbox));
        doThrow(new IllegalStateException("task insert failed")).when(failing).insertEvent(any());
        assertThatThrownBy(() -> maintenanceService(store, failing).close(message.orderId(), "operator-1", "任务写入失败验证"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(requests.findByRequest(1L, "close-rollback").getStatus()).isEqualTo(SeckillRequestStatus.ACCEPTED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_mq_outbox WHERE event_type='RELEASE_RESERVATION'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_seckill_maintenance_audit", Integer.class)).isZero();
        assertThat(stock()).isEqualTo("9");
    }

    /** 进程退出后的过期本地任务租约可接管，旧令牌不能覆盖新执行结果。 */
    @Test
    void expiredReservationLeaseIsTakenOver() {
        SeckillOrderMessage message = acceptedMessage(1L, "local-lease-takeover");
        SeckillMaintenanceServiceImpl worker = maintenanceService(store, outbox);
        worker.close(message.orderId(), "operator-1", "任务进程退出验证");
        jdbc.update("UPDATE tb_mq_outbox SET status='SENDING',lease_token='old-owner',lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE event_type='RELEASE_RESERVATION'");
        assertThat(worker.processReservationTasks()).isEqualTo(1);
        assertThat(outbox.markSent("release-" + message.orderId(), "old-owner", "late-receipt")).isZero();
        assertThat(stock()).isEqualTo("10");
        assertThat(jdbc.queryForObject("SELECT broker_message_id FROM tb_mq_outbox WHERE event_type='RELEASE_RESERVATION'", String.class)).isEqualTo("redis-released");
    }

    /** PREPARING 恢复遇到已释放预占时暂停活动，不把冲突误当正常重试继续扣库。 */
    @Test
    void preparingConflictPausesWithoutNewReservation() {
        MqOutboxMapper failing = mock(MqOutboxMapper.class, delegatesTo(outbox));
        doThrow(new IllegalStateException("outbox failed")).when(failing).insertEvent(any());
        assertThatThrownBy(() -> service(failing, store).accept(1L, voucherId, "preparing-conflict", "trace"))
                .isInstanceOf(SeckillAcceptancePendingException.class);
        Long id = requests.findByRequest(1L, "preparing-conflict").getOrderId();
        store.finish(voucherId, 1L, id, 1L, false, false);
        makeRequestsStale();
        maintenanceService(store, outbox).recoverDue();
        assertThat(stock()).isEqualTo("10");
        assertThat(requests.findByRequest(1L, "preparing-conflict").getStatus()).isEqualTo(SeckillRequestStatus.PREPARING);
        assertThat(jdbc.queryForObject("SELECT status FROM tb_seckill_campaign_control", String.class)).isEqualTo("PAUSED");
    }

    /**
     * 显式关闭与消费者竞争同一请求，只能得到成功或关闭之一，补偿后两端库存一致。
     * @throws Exception 并发任务失败或超时
     */
    @Test
    void closeAndConsumerRaceHasOneTerminalOutcome() throws Exception {
        SeckillOrderMessage message = acceptedMessage(1L, "close-consume-race");
        SeckillMaintenanceServiceImpl worker = maintenanceService(store, outbox);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> creating = pool.submit(() -> {
                try { start.await(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                consumeWithRetry(orderService(outbox, orders), message);
            });
            Future<?> closing = pool.submit(() -> {
                try { start.await(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                for (int attempt = 0; attempt < 100; attempt++) {
                    try {
                        worker.close(message.orderId(), "operator-1", "关闭和建单并发验证");
                        return;
                    } catch (org.springframework.dao.PessimisticLockingFailureException locked) {
                        try { Thread.sleep(10); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                    } catch (IllegalStateException terminal) {
                        if (requests.findByRequest(1L, "close-consume-race").getStatus() == SeckillRequestStatus.SUCCEEDED) {
                            return;
                        }
                        throw terminal;
                    }
                }
                throw new IllegalStateException("关闭竞争未收敛");
            });
            start.countDown(); creating.get(10, TimeUnit.SECONDS); closing.get(10, TimeUnit.SECONDS);
            worker.processReservationTasks();
            String state = jdbc.queryForObject("SELECT status FROM tb_seckill_request", String.class);
            int expectedStock = "SUCCEEDED".equals(state) ? 9 : 10;
            assertThat(state).isIn("SUCCEEDED", "CLOSED");
            assertThat(stock()).isEqualTo(String.valueOf(expectedStock));
            assertThat(jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher", Integer.class)).isEqualTo(expectedStock);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class)).isEqualTo("SUCCEEDED".equals(state) ? 1 : 0);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 真实 Broker 将隔离组的单条失败消息转入死信，正式监听器提交数据库隔离后确认。
     * @throws Exception 客户端配置、死信等待或数据库核对失败
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "ROCKETMQ_SMOKE_TEST", matches = "true")
    void realBrokerDeadLetterIsDurablyQuarantined() throws Exception {
        SeckillOrderMessage message = acceptedMessage(1L, "real-dlq");
        String suffix = UUID.randomUUID().toString().replace("-", "");
        RocketMqProperties properties = new RocketMqProperties();
        properties.setNameServer(System.getenv().getOrDefault("ROCKETMQ_NAMESERVER", "10.211.55.9:9876"));
        properties.setOrderTopic(System.getenv().getOrDefault("ROCKETMQ_SMOKE_TOPIC", "hmdp-order-smoke"));
        assertThat(properties.getOrderTopic()).startsWith("hmdp-order-smoke");
        properties.setProducerGroup("hmdp-phase4-producer-" + suffix);
        properties.setAccessKey(System.getenv("ROCKETMQ_ACCESS_KEY")); properties.setSecretKey(System.getenv("ROCKETMQ_SECRET_KEY"));
        var consumerProperties = new com.hmdp.config.OrderConsumerProperties();
        consumerProperties.setGroup("hmdp-phase4-consumer-" + suffix);
        consumerProperties.setDeadLetterGroup("hmdp-phase4-dlq-" + suffix);
        consumerProperties.setDeadLetterEnabled(true); consumerProperties.setThreads(1);
        RocketMqConfiguration configuration = new RocketMqConfiguration(properties);
        DefaultMQProducer producer = configuration.rocketMqProducer();
        com.hmdp.service.ISeckillOrderService unavailable = mock(com.hmdp.service.ISeckillOrderService.class);
        doThrow(new IllegalStateException("test forced retry")).when(unavailable).createFromMessage(any());
        OrderMessageFailureStore failures = new OrderMessageFailureStore(maintenanceMapper, manager);
        RocketMqOrderListener business = new RocketMqOrderListener(json, unavailable, failures);
        DefaultMQPushConsumer consumer = configuration.rocketMqOrderConsumer(consumerProperties, business, unavailable);
        RocketMqDeadLetterListener deadLetters = new RocketMqDeadLetterListener(failures);
        DefaultMQPushConsumer observer = configuration.rocketMqDeadLetterConsumer(consumerProperties, deadLetters);
        CountDownLatch isolated = new CountDownLatch(1);
        consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
            for (var raw : messages) {
                if (raw.getKeys() != null && Arrays.asList(raw.getKeys().split(" ")).contains(message.eventId())) {
                    // SDK 明确约定 -1 直接进入本组 DLQ，仅用于单条隔离验证，不是生产重试策略。
                    context.setDelayLevelWhenNextConsume(-1);
                    return business.consumeMessage(List.of(raw), context);
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        observer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
            ConsumeConcurrentlyStatus result = deadLetters.consumeMessage(messages, context);
            if (result == ConsumeConcurrentlyStatus.CONSUME_SUCCESS) {
                isolated.countDown();
            }
            return result;
        });
        try {
            producer.start();
            // 只创建随机测试组的 DLQ，并显式给测试 Topic 可读权限；不修改正式 Topic。
            producer.createTopic("TBW102", "%DLQ%" + consumerProperties.getGroup(), 1, Map.of());
            observer.start(); consumer.start();
            new RocketMqOrderPublisher(producer, json, properties).publish(message);
            assertThat(isolated.await(45, TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_seckill_message_quarantine WHERE source='DLQ'", Integer.class)).isEqualTo(1);
            assertThat(requests.findByRequest(1L, "real-dlq").getStatus()).isEqualTo(SeckillRequestStatus.ACCEPTED);
            assertThat(stock()).isEqualTo("9");
        } finally {
            observer.shutdown(); consumer.shutdown(); producer.shutdown();
        }
    }

    /**
     * 扫描快照仍为 PREPARING、真实请求已正常建单时，恢复不得误暂停活动。
     */
    @Test
    void stalePreparingSnapshotDoesNotPauseSucceededCampaign() {
        SeckillOrderMessage message = acceptedMessage(1L, "stale-preparing-snapshot");
        var staleCandidate = requests.findByRequest(1L, "stale-preparing-snapshot");
        staleCandidate.setStatus(SeckillRequestStatus.PREPARING);
        orderService(outbox, orders).createFromMessage(message);
        SeckillMaintenanceMapper staleScan = mock(SeckillMaintenanceMapper.class, delegatesTo(maintenanceMapper));
        doReturn(List.of(staleCandidate)).when(staleScan).staleRequests(anyInt(), anyInt());
        SeckillMaintenanceServiceImpl worker = new SeckillMaintenanceServiceImpl(staleScan, campaigns, requests,
                orders, outbox, store, service(outbox, store), voucherService(store, campaigns), json, manager,
                new SeckillOrderProperties(), new SeckillMaintenanceProperties());
        assertThat(worker.recoverDue()).isEqualTo(1);
        assertThat(requests.findByRequest(1L, "stale-preparing-snapshot").getStatus()).isEqualTo(SeckillRequestStatus.SUCCEEDED);
        assertThat(jdbc.queryForObject("SELECT status FROM tb_seckill_campaign_control WHERE voucher_id=?", String.class, voucherId)).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_seckill_maintenance_audit WHERE action='CONSISTENCY_CONFLICT'", Integer.class)).isZero();
    }

    /**
     * 通过不响应 Redis 协议的隔离 TCP 端口制造真实连接超时，受理保留原账本，恢复后不换订单号。
     * @throws Exception 隔离套接字初始化或关闭失败
     */
    @Test
    void realRedisConnectionTimeoutKeepsRequestRecoverable() throws Exception {
        try (var unavailableEndpoint = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            LettuceConnectionFactory unavailableFactory = unavailableRedisFactory(unavailableEndpoint.getLocalPort());
            try {
                SeckillReservationStore unavailableStore = new SeckillReservationStore(new StringRedisTemplate(unavailableFactory));
                assertThatThrownBy(() -> service(outbox, unavailableStore).accept(1L, voucherId, "redis-offline", "trace"))
                        .isInstanceOf(SeckillAcceptancePendingException.class);
                var retainedRequest = requests.findByRequest(1L, "redis-offline");
                assertThat(retainedRequest.getStatus()).isEqualTo(SeckillRequestStatus.PREPARING);
                assertThat(count("tb_mq_outbox")).isZero();
                assertThat(stock()).isEqualTo("10");
                SeckillAcceptance resumed = service(outbox, store).accept(1L, voucherId, "redis-offline", "trace");
                assertThat(resumed.orderId()).isEqualTo(retainedRequest.getOrderId());
                assertThat(resumed.status()).isEqualTo(SeckillRequestStatus.ACCEPTED);
                assertThat(stock()).isEqualTo("9");
            } finally {
                unavailableFactory.destroy();
            }
        }
    }

    /**
     * 确认任务遇到真实 Redis 连接超时仍保留，连接恢复后完成且不再次扣库存。
     * @throws Exception 隔离套接字初始化或关闭失败
     */
    @Test
    void realRedisConnectionTimeoutRetainsConfirmationTask() throws Exception {
        SeckillOrderMessage message = acceptedMessage(1L, "confirm-redis-offline");
        orderService(outbox, orders).createFromMessage(message);
        try (var unavailableEndpoint = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            LettuceConnectionFactory unavailableFactory = unavailableRedisFactory(unavailableEndpoint.getLocalPort());
            try {
                SeckillReservationStore unavailableStore = new SeckillReservationStore(new StringRedisTemplate(unavailableFactory));
                assertThat(maintenanceService(unavailableStore, outbox).processReservationTasks()).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT status FROM tb_mq_outbox WHERE event_id=?", String.class,
                        "confirm-" + message.orderId())).isEqualTo("PENDING");
                assertThat(requests.findByRequest(1L, "confirm-redis-offline").getStatus()).isEqualTo(SeckillRequestStatus.SUCCEEDED);
                assertThat(jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=?", Integer.class, voucherId)).isEqualTo(9);
            } finally {
                unavailableFactory.destroy();
            }
        }
        jdbc.update("UPDATE tb_mq_outbox SET next_retry_at=UTC_TIMESTAMP(3) WHERE event_id=?", "confirm-" + message.orderId());
        assertThat(maintenanceService(store, outbox).processReservationTasks()).isEqualTo(1);
        assertThat(store.verify(voucherId, 1L, message.orderId(), 1L)).isEqualTo("CONFIRMED");
        assertThat(stock()).isEqualTo("9");
    }

    /**
     * 1000 个不同用户竞争 100 份库存，重复消费后完成全部预占确认，在停止流量的边界检查联合不变量。
     * 使用真实 MySQL/Redis；消息直接交给正式建单服务，不把本用例耗时解释为 Broker 或 HTTP 吞吐量。
     * @throws Exception 并发任务失败或超时
     */
    @Test
    void thousandUsersConvergeToOneHundredOrders() throws Exception {
        jdbc.update("UPDATE tb_seckill_voucher SET stock=100 WHERE voucher_id=?", voucherId);
        redis.opsForValue().set(SeckillReservationStore.keys(voucherId, 1L).get(0), "100");
        SeckillRequestServiceImpl acceptance = service(outbox, store);
        ExecutorService workers = Executors.newFixedThreadPool(16);
        long startedAtNanos = System.nanoTime();
        try {
            List<Callable<SeckillAcceptance>> submissions = new ArrayList<>();
            for (long userId = 1; userId <= 1000; userId++) {
                long requestUserId = userId;
                submissions.add(() -> acceptance.accept(requestUserId, voucherId, "load-" + requestUserId, "load-trace"));
            }
            long acceptedCount = 0;
            for (Future<SeckillAcceptance> submission : workers.invokeAll(submissions, 180, TimeUnit.SECONDS)) {
                if (submission.get(1, TimeUnit.SECONDS).status() == SeckillRequestStatus.ACCEPTED) acceptedCount++;
            }
            assertThat(acceptedCount).isEqualTo(100);
            assertThat(stock()).isEqualTo("0");
            List<String> payloads = jdbc.queryForList("SELECT payload FROM tb_mq_outbox WHERE event_type='CREATE_ORDER'", String.class);
            SeckillOrderServiceImpl consumer = orderService(outbox, orders);
            List<Callable<Void>> deliveries = new ArrayList<>();
            for (String payload : payloads) {
                SeckillOrderMessage message = json.readValue(payload, SeckillOrderMessage.class);
                for (int duplicate = 0; duplicate < 2; duplicate++) deliveries.add(() -> {
                    consumeWithRetry(consumer, message);
                    return null;
                });
            }
            for (Future<Void> delivery : workers.invokeAll(deliveries, 60, TimeUnit.SECONDS)) delivery.get(1, TimeUnit.SECONDS);
            SeckillMaintenanceServiceImpl settlement = maintenanceService(store, outbox);
            for (int round = 0; round < 6; round++) settlement.processReservationTasks();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class)).isEqualTo(100);
            assertThat(jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=?", Integer.class, voucherId)).isZero();
            assertThat(stock()).isEqualTo("0");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_seckill_request WHERE status='SUCCEEDED'", Integer.class)).isEqualTo(100);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_seckill_request WHERE status='REJECTED'", Integer.class)).isEqualTo(900);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_seckill_request WHERE status IN ('PREPARING','ACCEPTED')", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_mq_outbox WHERE event_type='CONFIRM_RESERVATION' AND status <> 'SENT'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order o JOIN tb_seckill_request r ON o.id=r.order_id WHERE r.status='CLOSED'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT user_id,voucher_id FROM tb_voucher_order GROUP BY user_id,voucher_id HAVING COUNT(*)>1) d", Integer.class)).isZero();
            System.out.println("PHASE5_COMPONENT_LOAD users=1000 concurrency=16 stock=100 orders=100 rejected=900 elapsedMillis="
                    + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos));
        } finally {
            workers.shutdownNow();
        }
    }

    /**
     * 创建指向隔离无响应端口的真实 Redis 客户端，短超时仅用于故障用例，不修改正常 Redis 服务。
     * @param unavailablePort 当前测试独占但不响应 Redis 的本地 TCP 端口
     * @return 已初始化的短超时客户端，调用方必须在 finally 中 destroy
     */
    private LettuceConnectionFactory unavailableRedisFactory(int unavailablePort) {
        RedisStandaloneConfiguration endpoint = new RedisStandaloneConfiguration("127.0.0.1", unavailablePort);
        LettuceConnectionFactory factory = new LettuceConnectionFactory(endpoint, LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(250)).shutdownTimeout(Duration.ZERO).build());
        factory.afterPropertiesSet();
        return factory;
    }

    /**
     * 组装真实补偿和恢复服务，不启用周期线程，按用例主动推进每一轮。
     * @param reservations 真实 Redis 组件或单点故障替身
     * @param events 真实 Outbox 或单点故障替身
     * @return 使用真实数据库事务的维护服务
     */
    private SeckillMaintenanceServiceImpl maintenanceService(SeckillReservationStore reservations, MqOutboxMapper events) {
        return new SeckillMaintenanceServiceImpl(maintenanceMapper, campaigns, requests, orders, events, reservations,
                service(outbox, store), voucherService(store, campaigns), json, manager,
                new SeckillOrderProperties(), new SeckillMaintenanceProperties());
    }

    /** 将隔离用例的未终态请求置为长期未更新，不实际等待一分钟。 */
    private void makeRequestsStale() {
        jdbc.update("UPDATE tb_seckill_request SET updated_at=TIMESTAMPADD(SECOND,-120,UTC_TIMESTAMP(3))");
    }

    /**
     * 接受请求并读取首次持久化建单消息。
     *
     * @param user      用户编号
     * @param requestId 请求编号
     * @return 原始建单消息
     */
    private SeckillOrderMessage acceptedMessage(Long user, String requestId) {
        SeckillAcceptance accepted = service(outbox, store).accept(user, voucherId, requestId, "trace-" + requestId);
        try {
            return json.readValue(jdbc.queryForObject("SELECT payload FROM tb_mq_outbox WHERE order_id=? AND event_type='CREATE_ORDER'",
                    String.class, accepted.orderId()), SeckillOrderMessage.class);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * 组装独立建单服务，允许替换一个故障点。
     *
     * @param events       待发事件 Mapper
     * @param targetOrders 订单 Mapper
     * @return 使用真实 MySQL 事务的建单服务
     */
    private SeckillOrderServiceImpl orderService(MqOutboxMapper events, VoucherOrderMapper targetOrders) {
        return new SeckillOrderServiceImpl(campaigns, requests, targetOrders, vouchers, events, json, manager, new SeckillOrderProperties());
    }

    /**
     * 模拟 MQ 对 NOWAIT 锁竞争的有界重试，其他异常立即暴露。
     *
     * @param consumer 建单服务
     * @param message  建单消息
     */
    private void consumeWithRetry(SeckillOrderServiceImpl consumer, SeckillOrderMessage message) {
        for (int attempt = 0; attempt < 100; attempt++) {
            try {
                consumer.createFromMessage(message);
                return;
            } catch (org.springframework.dao.PessimisticLockingFailureException locked) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
        }
        throw new IllegalStateException("锁竞争重试次数用尽");
    }

    /**
     * 组装真实数据库受理服务，按用例替换单个故障点。
     *
     * @param events       Outbox Mapper，正常或故障替身
     * @param reservations Redis 预占组件，正常或故障替身
     * @return 不依赖 Spring 代理的受理实现
     */
    private SeckillRequestServiceImpl service(MqOutboxMapper events, SeckillReservationStore reservations) {
        return new SeckillRequestServiceImpl(requests, campaigns, events, reservations, ids, json, manager, new SeckillOrderProperties());
    }

    /**
     * 构造单次只领取一条的测试投递器。
     *
     * @param events    Outbox Mapper
     * @param publisher 发送替身
     * @return 待测试投递器
     */
    private OrderOutboxDispatcher dispatcher(MqOutboxMapper events, RocketMqOrderPublisher publisher) {
        OrderOutboxProperties properties = new OrderOutboxProperties();
        properties.setBatchSize(1);
        return new OrderOutboxDispatcher(events, publisher, json, manager, properties);
    }

    /**
     * 获取当前隔离券的 Redis 可用库存。
     *
     * @return 十进制库存值
     */
    private String stock() {
        return redis.opsForValue().get(SeckillReservationStore.keys(voucherId, 1L).get(0));
    }

    /**
     * 统计限定测试表内的记录数。
     *
     * @param table 固定白名单表名
     * @return 表内记录数
     */
    private int count(String table) {
        if (!Set.of("tb_mq_outbox", "tb_seckill_request").contains(table))
            throw new IllegalArgumentException("未知测试表");
        return Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
    }
}
