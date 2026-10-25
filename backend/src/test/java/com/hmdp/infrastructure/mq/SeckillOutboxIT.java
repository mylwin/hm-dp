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
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.service.impl.SeckillOrderServiceImpl;
import com.hmdp.service.impl.SeckillRequestServiceImpl;
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
import org.mybatis.spring.SqlSessionFactoryBean;
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

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
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
        new ResourceDatabasePopulator(new ClassPathResource("db/seckill-order-tables.sql")).execute(dataSource);
        ResourceDatabasePopulator orderIndex = new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V20261024_03__seckill_order_unique.sql"));
        orderIndex.execute(dataSource);
        orderIndex.execute(dataSource);
        manager = new DataSourceTransactionManager(dataSource);
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(SeckillRequestMapper.class);
        configuration.addMapper(SeckillCampaignControlMapper.class);
        configuration.addMapper(MqOutboxMapper.class);
        configuration.addMapper(VoucherOrderMapper.class);
        configuration.addMapper(VoucherMapper.class);
        configuration.addMapper(SeckillVoucherMapper.class);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.setMapperLocations(new ClassPathResource("mapper/VoucherMapper.xml"));
        SqlSessionFactory sessions = Objects.requireNonNull(factory.getObject());
        SqlSessionTemplate template = new SqlSessionTemplate(sessions);
        requests = template.getMapper(SeckillRequestMapper.class);
        campaigns = template.getMapper(SeckillCampaignControlMapper.class);
        outbox = template.getMapper(MqOutboxMapper.class);
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
        RocketMqOrderListener listener = new RocketMqOrderListener(json, business);
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
