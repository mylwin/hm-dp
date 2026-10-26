# 订单 RocketMQ 第一阶段：客户端接入与收发验证

日期：2026-10-24。

## 范围

本轮只优化订单，点赞、评论、关注、博客 Feed、通知保持原实现。
不增加支付、退款或超时未支付关单。本阶段不修改 `VoucherOrderServiceImpl`、秒杀 Lua 或 Stream 消费启动逻辑。

| 阶段 | 内容 | 状态 |
|---|---|---|
| 1 | 版本与地址核实、客户端配置、同步订单发布入口、独立收发验证 | 已完成并验证 |
| 2 | 请求账本、预占状态、MySQL Outbox、租约领取与退避投递 | 已实现并完成隔离验证；组件默认关闭，见[第二阶段说明](ROCKETMQ_PHASE2.md) |
| 3 | 幂等建单、请求终态、归属校验、前端结果查询 | 待实施 |
| 4 | 关闭与补偿、周期对账、积压准入、Redis 受控重建 | 待实施 |
| 5 | 故障压测、排空旧 Stream、安全切换与回滚演练 | 待实施 |

第二至第五阶段完成并验证前，不能将第一阶段的发布入口直接串接到 Lua 预扣后并停用 Stream。
客户端连通不代表已解决跨 Redis/MySQL/MQ 的一致性。

## 已核实的虚拟机配置

通过 NameServer 路由、Broker 运行状态与配置的只读请求获取：

| 项目 | 值 |
|---|---|
| Broker 版本 | V5_3_2 |
| NameServer | 10.211.55.9:9876 |
| 集群 / Broker | DefaultCluster / broker-a |
| 对外注册地址 | 10.211.55.9:10911 |
| ACL | aclEnable=false |
| 开发环境自动建 Topic | autoCreateTopicEnable=true |
| Broker 角色 / 刷盘方式 | ASYNC_MASTER / ASYNC_FLUSH |

本阶段未修改虚拟机 Broker 配置。独立收发测试可能自动创建测试 Topic 和临时消费组，
这些开发测试数据保留到服务端正常清理。该部署状态不构成生产高可用或同步复制保证。

## 实现

- 使用 `rocketmq-client` 与 `rocketmq-acl` 5.3.2，和已确认的 Broker 对齐。
  通过普通 Spring 配置管理客户端，暂不引入额外 starter 自动配置或 gRPC Proxy 接入依赖。
- `RocketMqConfiguration`：使用 `@RequiredArgsConstructor` 注入配置；以 `@Bean` 注册第三方生产者，启动/关闭由 Spring 调用。
- `RocketMqProperties`：使用 `@Component` 注册，由 `@ConfigurationProperties` 绑定属性；`RocketMqConfiguration` 不再使用 `@EnableConfigurationProperties` 重复注册。
  校验地址、Topic、生产组、超时、重试数及成对 ACL 凭据；`@Data` 的 toString 排除 ACL 凭据。
- `SeckillOrderMessage`：固定 schemaVersion=1，携带 eventId、orderId、userId、voucherId、traceId、acceptedAt。
  发送重试使用原业务标识，发送器不自行重新生成。
- `RocketMqOrderPublisher`：使用 `@Component + @RequiredArgsConstructor` 注入生产者、ObjectMapper 和配置，和生产者使用相同启用条件。
  同步发送，消息 Key 包含 eventId 和 orderId，附带 traceId；配置类不再以 `@Bean` 重复注册发布器。
  只有 `SEND_OK` 返回回执；超时、其他状态或异常向调用方传播，不访问 Redis、不回补库存。
- 中断异常保留线程中断标志，便于后续任务优雅停止。

`PublishReceipt` 只表示 Broker 的发送回执，不表示订单创建成功。
第一阶段没有常驻订单消费者；接收验证仅位于显式运行的集成测试中。

## 配置

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| ROCKETMQ_ENABLED | true | 当前 application.yaml 默认值；只控制客户端初始化，不切换业务通道 |
| ROCKETMQ_NAMESERVER | 10.211.55.9:9876 | 多个地址用分号分隔 |
| ROCKETMQ_PRODUCER_GROUP | hmdp-order-producer | 订单生产者组 |
| ROCKETMQ_ORDER_TOPIC | hmdp-seckill-order-create | 后续订单业务 Topic |
| ROCKETMQ_SEND_TIMEOUT | 3s | 单次发送超时 |
| ROCKETMQ_SEND_RETRIES | 0 | SDK 内部重试次数，后续重试交给 Outbox |
| ROCKETMQ_VIP_CHANNEL_ENABLED | false | 使用注册地址端口，不默认切换到 VIP 端口 |
| ROCKETMQ_ACCESS_KEY / ROCKETMQ_SECRET_KEY | 空 | 可选，必须成对配置 |

`enabled=true` 时配置错误导致启动失败；关闭客户端不意味丢弃任何已受理业务事件。
`producer.start()` 初始化成功不能证明 Topic 路由和 Broker 收发可用，实际收发由下面测试验证。

## 验证入口

普通单元测试：

```bash
mvn -f backend/pom.xml -Dtest=RocketMqConfigurationTest,RocketMqPropertiesTest,RocketMqOrderPublisherTest test
```

独立真实服务收发测试：

```bash
ROCKETMQ_SMOKE_TEST=true mvn -f backend/pom.xml -Dtest=RocketMqSmokeIT test
```

测试地址可用 `ROCKETMQ_NAMESERVER` 覆盖，测试 Topic 可用 `ROCKETMQ_SMOKE_TOPIC` 覆盖，
但只允许 `hmdp-order-smoke` 前缀，避免误向真实订单 Topic 发送合成订单。
每次运行使用唯一 eventId、独立生产者组和独立消费组，最多等待 30 秒接收当前探针。
测试逐字节校验消息体和 traceId，最后关闭客户端。
不启动应用、数据库、Redis 和现有订单消费者，也不扣减真实券库存。

## 验证记录

2026-10-24 已执行：

- Java 17 / Spring Boot 3.5.9 编译通过。
- 新增 21 个单元测试通过：启停、禁用不创建客户端、配置校验、ACL 钩子、稳定消息标识、非 SEND_OK、超时与中断传播。
- 全部 `*Test` 纯单元测试共 117 个通过，无失败或跳过。
- 独立 `RocketMqSmokeIT` 1 个通过：向虚拟机隔离 Topic 发送事件，并在 30 秒期限内接收、校验消息体与 traceId。
- `git diff --check` 通过。
- 依赖检查确认 Netty 由 Spring Boot 统一管理为 4.1.130.Final；真实收发与现有单元测试均通过。

Mockito 测试在受限执行环境内因 JVM attach 被禁止而无法初始化，获得本地执行权限后同组测试全部通过。
未运行 `HmDianPingApplicationTests`，它依赖真实数据库/Redis，并含手工数据写入；本阶段不执行这类数据准备测试。
未进行订单业务压测、Broker 宕机重投、真实订单消费或 Stream 迁移，以上属于后续阶段。

2026-10-24 注入风格检查：配置类和发布器统一使用 `@RequiredArgsConstructor`，发布器以 `@Component` 注册；
删除发布器工厂方法，保留第三方生产者的 `@Bean` 生命周期。保留用户选择的 `@Data`，凭据字段排除 toString。
配置测试通过实际组件扫描检查开关和唯一注册；本次 119 个单元测试通过，其中 MQ 相关为 23 个。

## 下一阶段边界

使用 `rocketmq-seckill-design.md` 的 MySQL 账本与 Outbox 协议；基础封装不使用“抢不到 Redis 幂等锁就 ACK”的通用模板。
暂不引入顺序消息、延迟关单、全站事件抽象或 Redis 轻量补偿表。
实际切换必须在迁移阶段排空旧 Stream 的未读与 pending 后进行，不能仅切换配置开关。
