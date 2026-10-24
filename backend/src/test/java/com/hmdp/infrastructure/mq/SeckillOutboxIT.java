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
        manager = new DataSourceTransactionManager(dataSource);
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(SeckillRequestMapper.class);
        configuration.addMapper(SeckillCampaignControlMapper.class);
        configuration.addMapper(MqOutboxMapper.class);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        SqlSessionFactory sessions = Objects.requireNonNull(factory.getObject());
        SqlSessionTemplate template = new SqlSessionTemplate(sessions);
        requests = template.getMapper(SeckillRequestMapper.class);
        campaigns = template.getMapper(SeckillCampaignControlMapper.class);
        outbox = template.getMapper(MqOutboxMapper.class);
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

    /** 为每个用例创建全新的隔离活动和 10 份库存。 */
    @BeforeEach
    void seed() {
        voucherId = 1000000000000L + (UUID.randomUUID().getMostSignificantBits() & 0x0FFFFFFFFFFFFFFFL);
        jdbc.update("INSERT INTO tb_seckill_campaign_control(voucher_id, epoch, status) VALUES (?, 1, 'READY')", voucherId);
        List<String> keys = SeckillReservationStore.keys(voucherId, 1L);
        assertThat(redis.hasKey(keys.get(0))).isFalse();
        redisKeys.addAll(keys);
        redis.opsForValue().set(keys.get(0), "10");
        redis.opsForHash().putAll(keys.get(1), Map.of("ready", "1", "epoch", "1",
                "beginAt", String.valueOf(Instant.now().minusSeconds(60).toEpochMilli()),
                "endAt", String.valueOf(Instant.now().plusSeconds(3600).toEpochMilli())));
    }

    /** 回收本用例创建的键和测试表记录，不扫描或删除业务键。 */
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
        }
    }

    /**
     * 只删除本类随机创建的测试库，关闭 Redis 客户端。
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

    /** 正常受理和重复请求只预占一次、登记一条消息，保留首次 traceId。 */
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
     * @throws Exception 并发任务失败时抛出
     */
    @Test
    void concurrentSameRequestHasOneDurableOrderId() throws Exception {
        SeckillRequestServiceImpl service = service(outbox, store);
        ExecutorService executor = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Long>> tasks = new ArrayList<>();
            for (int i = 0; i < 12; i++) tasks.add(() -> {
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

    /** Outbox 写失败必须回滚 ACCEPTED，保留 PREPARING 和 Redis 预占供原请求恢复。 */
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

    /** Lua 实际已成功但客户端超时，账本必须可通过同一 requestId 恢复。 */
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

    /** 新 requestId 也不能绕过同一用户对同一张券的预占资格。 */
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

    /** 未开始、已结束和库存不足均是已知拒绝，不消耗库存、不登记事件。 */
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

    /** 元数据失效或维护暂停时保留 PREPARING，不能把可能存在的预占当成已拒绝。 */
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

    /** 旧代次、错误键类型和 RELEASED 墓碑都不能触发新的扣减。 */
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

    /** 首次拒绝写入终态，即使库存后来恢复，迟到的同订单命令也不能重新预占。 */
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

    /** 旧进程的回执和重试不能覆盖租约接管者的新状态。 */
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

    /** 模拟数据库已提交但提交响应丢失，客户端重试仍复用同一订单及唯一事件。 */
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

    /** Broker 已收消息但数据库回执保存失败，租约过期后重发原事件，不回补库存。 */
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
     * 组装真实数据库受理服务，按用例替换单个故障点。
     * @param events Outbox Mapper，正常或故障替身
     * @param reservations Redis 预占组件，正常或故障替身
     * @return 不依赖 Spring 代理的受理实现
     */
    private SeckillRequestServiceImpl service(MqOutboxMapper events, SeckillReservationStore reservations) {
        return new SeckillRequestServiceImpl(requests, campaigns, events, reservations, ids, json, manager, new SeckillOrderProperties());
    }

    /**
     * 构造单次只领取一条的测试投递器。
     * @param events Outbox Mapper
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
     * @return 十进制库存值
     */
    private String stock() {
        return redis.opsForValue().get(SeckillReservationStore.keys(voucherId, 1L).get(0));
    }

    /**
     * 统计限定测试表内的记录数。
     * @param table 固定白名单表名
     * @return 表内记录数
     */
    private int count(String table) {
        if (!Set.of("tb_mq_outbox", "tb_seckill_request").contains(table)) throw new IllegalArgumentException("未知测试表");
        return Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
    }
}
