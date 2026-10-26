package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.hmdp.config.SeckillOrderProperties;
import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.infrastructure.seckill.SeckillReservationStore;
import com.hmdp.mapper.SeckillCampaignControlMapper;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 仅连接真实 MySQL 的普通券购买验收；随机建库，不连接 Redis 或 RocketMQ。 */
@EnabledIfEnvironmentVariable(named = "ORDINARY_VOUCHER_IT", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrdinaryVoucherPurchaseIT {
    private final String database = "hmdp_coupon_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ISeckillVoucherService seckillStock = mock(ISeckillVoucherService.class);
    private final SeckillCampaignControlMapper campaigns = mock(SeckillCampaignControlMapper.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<SeckillReservationStore> reservationStores = mock(ObjectProvider.class);
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactionManager;
    private VoucherMapper vouchers;
    private VoucherOrderMapper orders;
    private String adminUrl;
    private String username;
    private String password;
    private boolean created;

    /**
     * 创建随机测试库，只加载既有业务表和用户券唯一索引，验证购买不依赖秒杀账本表。
     * @throws Exception 数据库连接或 Mapper 初始化失败
     */
    @BeforeAll
    void connect() throws Exception {
        adminUrl = "jdbc:mysql://" + System.getenv().getOrDefault("MYSQL_HOST", "127.0.0.1") + ":"
                + System.getenv().getOrDefault("MYSQL_PORT", "3306") + "/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        username = System.getenv().getOrDefault("MYSQL_USERNAME", "root");
        password = System.getenv().getOrDefault("MYSQL_PASSWORD", "123456");
        try (Connection connection = DriverManager.getConnection(adminUrl, username, password)) {
            connection.createStatement().execute("CREATE DATABASE `" + database + "`");
            created = true;
        }
        var dataSource = new DriverManagerDataSource(adminUrl.replace("/?", "/" + database + "?"), username, password);
        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        new ResourceDatabasePopulator(new ClassPathResource("db/seckill-order-tables.sql"),
                new ClassPathResource("db/migration/V20261024_03__seckill_order_unique.sql")).execute(dataSource);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(VoucherMapper.class);
        configuration.addMapper(VoucherOrderMapper.class);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource); factory.setConfiguration(configuration);
        SqlSessionTemplate sessions = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        vouchers = sessions.getMapper(VoucherMapper.class);
        orders = sessions.getMapper(VoucherOrderMapper.class);
    }

    /** 每个用例准备一张上架普通券，不创建秒杀库存。 */
    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO tb_voucher(id,shop_id,title,pay_value,actual_value,type,status) VALUES(1,1,'普通券',5000,10000,0,1)");
    }

    /** 清理本测试库用例数据，并确认购买没有调用 Redis、秒杀库存或预占组件。 */
    @AfterEach
    void cleanup() {
        verifyNoInteractions(redis, seckillStock, campaigns, reservationStores);
        if (jdbc != null) {
            jdbc.update("DELETE FROM tb_voucher_order");
            jdbc.update("DELETE FROM tb_voucher");
        }
    }

    /**
     * 只回收本类创建的随机数据库，不删除任何业务库。
     * @throws Exception 测试库关闭失败
     */
    @AfterAll
    void disconnect() throws Exception {
        if (created && database.matches("hmdp_coupon_it_[a-f0-9]{12}")) {
            try (Connection connection = DriverManager.getConnection(adminUrl, username, password)) {
                connection.createStatement().execute("DROP DATABASE `" + database + "`");
            }
        }
    }

    /** 普通购买创建未支付订单，不同用户可购买同一普通券且不要求秒杀库存记录。 */
    @Test
    void createsUnpaidOrdersWithoutSeckillStock() {
        Result first = service(orders).purchaseVoucher(7L, 1L);
        Result second = service(orders).purchaseVoucher(8L, 1L);
        assertThat(first.getSuccess()).isTrue();
        assertThat(first.getData()).isInstanceOf(String.class);
        assertThat(second.getSuccess()).isTrue();
        assertThat(second.getData()).isNotEqualTo(first.getData());
        VoucherOrder savedOrder = orders.findOrder(Long.valueOf((String) first.getData()));
        assertThat(savedOrder.getStatus()).isEqualTo(1);
        assertThat(savedOrder.getUserId()).isEqualTo(7L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_seckill_voucher", Integer.class)).isZero();
    }

    /** 重试找回原订单，券下架和订单支付状态变动后也不创建第二张或覆盖原状态。 */
    @Test
    void retryReturnsOriginalOrderAfterVoucherIsTakenOffSale() {
        Result first = service(orders).purchaseVoucher(7L, 1L);
        jdbc.update("UPDATE tb_voucher SET status=2 WHERE id=1");
        jdbc.update("UPDATE tb_voucher_order SET status=2 WHERE user_id=7");
        assertThat(service(orders).purchaseVoucher(7L, 1L).getData()).isEqualTo(first.getData());
        assertThat(service(orders).purchaseVoucher(8L, 1L).getSuccess()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM tb_voucher_order", Integer.class)).isEqualTo(2);
    }

    /** 不存在、下架和秒杀类型均不能从普通入口创建订单，非法身份在写库前拒绝。 */
    @Test
    void rejectsUnavailableAndSeckillVouchers() {
        VoucherServiceImpl purchases = service(orders);
        assertThat(purchases.purchaseVoucher(7L, 99L).getSuccess()).isFalse();
        jdbc.update("UPDATE tb_voucher SET status=2 WHERE id=1");
        assertThat(purchases.purchaseVoucher(7L, 1L).getSuccess()).isFalse();
        jdbc.update("UPDATE tb_voucher SET status=1,type=1 WHERE id=1");
        assertThat(purchases.purchaseVoucher(7L, 1L).getSuccess()).isFalse();
        assertThatThrownBy(() -> purchases.purchaseVoucher(null, 1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> purchases.purchaseVoucher(7L, 0L)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class)).isZero();
    }

    /**
     * 同一用户并发提交 16 次只产生一个订单号；券锁覆盖到提交，后续线程才能读取原订单。
     * @throws Exception 并发任务失败或超时
     */
    @Test
    void concurrentRetriesCreateOneOrder() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try {
            VoucherServiceImpl purchases = service(orders);
            List<Callable<Object>> attempts = new ArrayList<>();
            for (int index = 0; index < 16; index++) attempts.add(() -> purchases.purchaseVoucher(7L, 1L).getData());
            Set<Object> orderIds = new HashSet<>();
            for (Future<Object> attempt : workers.invokeAll(attempts, 15, TimeUnit.SECONDS)) orderIds.add(attempt.get());
            assertThat(orderIds).hasSize(1).doesNotContainNull();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class)).isEqualTo(1);
        } finally {
            workers.shutdownNow();
        }
    }

    /** 实际插入后抛异常时整笔购买回滚，后续正常重试仍可创建订单。 */
    @Test
    void failedPurchaseRollsBackInsertedOrder() {
        VoucherOrderMapper failingOrders = mock(VoucherOrderMapper.class, delegatesTo(orders));
        doAnswer(call -> {
            orders.insert((VoucherOrder) call.getArgument(0));
            throw new IllegalStateException("模拟插入后异常");
        }).when(failingOrders).insert(any(VoucherOrder.class));
        assertThatThrownBy(() -> service(failingOrders).purchaseVoucher(7L, 1L)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class)).isZero();
        assertThat(service(orders).purchaseVoucher(7L, 1L).getSuccess()).isTrue();
    }

    /**
     * 组装真实数据库购买服务，其他依赖均为不允许被调用的替身。
     * @param orderMapper 真实订单 Mapper 或单点写入故障替身
     * @return 使用真实独立事务的优惠券业务实现
     */
    private VoucherServiceImpl service(VoucherOrderMapper orderMapper) {
        VoucherServiceImpl purchases = new VoucherServiceImpl(seckillStock, redis, campaigns, reservationStores,
                transactionManager, new SeckillOrderProperties(), orderMapper);
        ReflectionTestUtils.setField(purchases, "baseMapper", vouchers);
        return purchases;
    }
}
