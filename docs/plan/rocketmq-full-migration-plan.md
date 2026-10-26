# hmdp 全量 RocketMQ 改造计划（秒杀 + 博客推送 + 通知）

> **2026-10-25 第五阶段范围调整**：后续只使用新秒杀路径，不再维护旧 Stream。默认入口、旧组件停用与故障并发验证见 [第五阶段说明](../optimization/order/ROCKETMQ_PHASE5.md)。以下旧链路迁移和回滚规划保留为历史。

> **2026-10-24 执行范围收敛**：按用户最新要求，本轮只实施订单优化。
> 点赞、评论、关注、博客推送、站内通知和支付超时关单均暂缓，不作为订单迁移的前置依赖。
> 下文的全站阶段和工期保留为历史规划，不代表当前执行顺序；当前按客户端接入、可靠受理与 Outbox、
> 幂等建单与结果查询、补偿对账、故障验收与切换五个阶段推进。
> 第一阶段已完成，见 [订单 RocketMQ 第一阶段说明](../optimization/order/ROCKETMQ_PHASE1.md)；
> 第二阶段已完成可靠受理、预占与 Outbox 的隔离验证，见 [第二阶段说明](../optimization/order/ROCKETMQ_PHASE2.md)，
> 第二阶段交付时新组件默认关闭，尚未切换 HTTP 入口或注册正式订单消费者；
> 后续可靠性协议以 [秒杀专项设计](rocketmq-seckill-design.md) 为准。

> 第三阶段已实现订单消费、幂等建单与结果查询；当前开发 YAML 默认启用新后端组件、API、Outbox 和消费者，前端需显式开启。
> 2026-10-25 真实前后端及虚拟机 MQ 隔离联调已通过，提交前完整后端回归 181 项全部通过；详情见
> [第三阶段说明](../optimization/order/ROCKETMQ_PHASE3.md)。以下旧规划中的“未注册正式消费者”仅指当时的第二阶段状态。

> 范围：把 `hm-dp` 里所有"异步/事件"场景统一到 RocketMQ。
> 基线：HEAD `203f97a`，Spring Boot 3.5.9 / Java 17，单模块 `backend`，Redis 7 / Redisson 3.52。
> 前置文档：`docs/plan/redis-stream-to-rocketmq-migration.md`（秒杀链路的原子性分析，本文在其基础上扩展到全站）；
> P3 配套设计：`docs/plan/rocketmq-seckill-design.md`（秒杀订单迁移方案，P3 以它为准）。
>
> 2026-10-23 修订：P3 与秒杀设计文档对齐——受理失败不再"回滚 Redis"，改走 MySQL Outbox 重投；新增 §5.2 可靠性分级；§10.1 增补独立参照工程对照。

---

## 0. 结论先行

**"后面推送博客这些能用 RocketMQ 处理不" → 能，而且比秒杀更该用。**

原因是两者的性质完全不同：

| | 秒杀下单 | 博客 Feed 推送 |
|---|---|---|
| 现状 | Redis Stream（已经是 MQ） | **同步 for 循环写 ZSet**（`saveBlog:305-309`） |
| 迁移性质 | **改造**（替换已有 MQ） | **新增 MQ**（把同步改异步） |
| 核心难点 | Lua 原子性被切断 | 无原子性冲突，**纯收益** |
| 风险 | 高（有钱相关） | 低（丢一条动态可接受） |
| 收益 | 消息堆积能力、死信、可观测 | **接口响应时间从 O(粉丝数) 降到 O(1)** |

**所以我的建议是：博客推送应该比秒杀更早做。** 它没有原子性问题、收益立竿见影、且是"MQ 用得对不对"的教科书场景。

本文给出三阶段计划：
- **P1（博客点赞/评论/关注事件）** → 最安全，先建立 MQ 基础设施与规范
- **P2（博客 Feed 推送）** → 主收益，接口提速
- **P3（秒杀下单）** → 最复杂，最后做，复用 P1/P2 沉淀的 outbox 与幂等能力

---

## 1. 现状测绘：哪些地方该上 MQ

### 1.1 全站异步/事件场景盘点

| # | 场景 | 现状代码 | 阻塞点 | 是否适合 MQ | 优先级 |
|---|---|---|---|---|---|
| 1 | **发博客 → 推给粉丝** | `saveBlog:301-309` 同步 `for` 循环 `ZADD` | 粉丝 1 万 → 1 万次 Redis 往返，接口卡死 | ✅ **最适合**（写扩散） | **P2 高** |
| 2 | **点赞 → 更新计数/榜单** | `likeBlog:128-141` 在锁内同步改 DB+Redis | 锁内做重活，3 秒租约易超时 | ✅ 适合（事件驱动） | P1 |
| 3 | **评论 → 博客评论数** | `BlogCommentsServiceImpl` **空实现，无任何评论逻辑** | 功能缺失 | ✅ 适合（顺带补功能） | P1 |
| 4 | **关注 → 粉丝数/Feed 初始化** | `follow:71-105` 只写 DB + Set | 无异步 | ✅ 适合（关注后补历史 Feed） | P1 |
| 5 | **秒杀下单** | `seckillVoucher:99-123` + Stream | 已有 MQ，原子性问题 | ✅ 适合（但要小心） | **P3 最高风险** |
| 6 | **订单超时未支付关单** | ❌ 无 | 功能缺失 | ✅ 延迟消息最优解 | P3 |
| 7 | **签到提醒 / 券到期提醒** | ❌ 无 | 功能缺失 | ✅ 延迟消息 | P4 可选 |
| 8 | 商铺缓存更新广播 | `updateShop:86-98` 只删本地 key | 多实例 L1 不一致 | 🟡 可用 MQ，但 Pub/Sub 更轻 | P4 |

### 1.2 重点：`saveBlog` 的三个真实问题（读代码发现）

```java
@Transactional(rollbackFor = Exception.class)   // :286
public Result saveBlog(Blog blog) {
    boolean success = save(blog);
    if (!success) return Result.fail("博客保存失败");

    List<Follow> fans = followService.query()                    // :302 全表扫粉丝
            .eq("follow_user_id", user.getId()).list();

    fans.forEach(fan -> {                                        // :305 同步循环
        stringRedisTemplate.opsForZSet().add(                 // :308 每次一次往返
                key, blog.getId().toString(), System.currentTimeMillis());
    });
    return Result.ok(blog.getId());
}
```

| 问题 | 说明 | MQ 怎么解决 |
|---|---|---|
| **① 事务与 Redis 不一致** | Redis 写**不在事务内**，DB 一旦回滚，Feed 里会留下指向不存在博客的脏条目（前端查出来是 null 被过滤掉，但 ZSet 永久脏数据） | 用 `@TransactionalEventListener(AFTER_COMMIT)` 或事务消息，**提交后才发** |
| **② 同步阻塞** | 粉丝 N 人 = N 次网络往返 + 1 次全表查询。1 万粉丝 ≈ 1-3 秒，接口直接超时 | 主线程只发 1 条消息，N 次写入交给消费者 |
| **③ 无失败补偿** | 循环中某次 `ZADD` 抛异常 → 已写的一半生效、一半丢失，且无重试 | MQ 重试 + 消费者批量写 |
| ④ 无索引 | `tb_follow` 只有主键，`where follow_user_id = ?` **全表扫描** | （与 MQ 无关，但必须一起修） |

> 顺带发现：`BlogCommentsServiceImpl` 是**完全空的实现**（只有 `extends ServiceImpl`），说明评论功能只有表没有逻辑。P1 可以顺带补齐，正好用 MQ 解耦"评论 → 更新计数 → 通知作者"。

### 1.3 现有 MQ 资产盘点

| 资产 | 状态 | 迁移后处置 |
|---|---|---|
| `seckill.lua:32` 的 `XADD` | 唯一 Stream 生产点 | 改模式分支（见秒杀文档 1.3） |
| `VoucherOrderServiceImpl:206-313` 消费器 | 单线程 + pending 重放 | 拆成 `SeckillOrderConsumer` |
| `RedisConstants.ORDER_MQ` | Stream key 常量 | 保留（回滚用） |
| Redis 其余用途 | 缓存/锁/Feed/点赞/签到/GEO | **不动**，MQ 只接管"异步事件" |

> 重要边界：**RocketMQ 不是替代 Redis**，只替代「Redis 当消息队列」这一种用法。缓存、分布式锁、ZSet 收件箱、BitMap 签到全部保留。

---

## 2. 基础设施设计（先做，所有场景复用）

### 2.1 Topic 规划

| Topic | 生产方 | 消费方 | 消息量级 | 顺序要求 | 延迟容忍 |
|---|---|---|---|---|---|
| `hmdp-seckill-order` | 秒杀接口 | 订单落库 | 峰值极高 | 按 voucherId 局部有序 | 1s |
| `hmdp-blog-publish` | 发博客 | Feed 推送 | 中 | 无序 | 5s |
| `hmdp-blog-like` | 点赞 | 计数/榜单/通知 | 高 | 按 blogId 有序 | 30s |
| `hmdp-blog-comment` | 评论 | 计数/通知作者 | 中 | 按 blogId 有序 | 1min |
| `hmdp-follow` | 关注/取关 | 粉丝数/历史 Feed 补推 | 低 | 按 userId 有序 | 1min |
| `hmdp-order-timeout` | 下单后 | 关单回补库存（延迟消息） | 低 | — | 延迟 15min |
| `hmdp-notify` | 各业务 | 站内信/推送 | 中 | 无序 | 5min |
| `%DLQ%*` | RocketMQ 自动 | 告警/人工 | 极低 | — | — |

**命名规范**：`hmdp-{domain}-{event}`，全小写中划线；消费组 `{topic}-consumer`；DLQ 用 RocketMQ 默认 `%DLQ%{consumerGroup}`。

### 2.2 统一消息信封（所有 Topic 复用）

```java
@Data
public class MqMessage<T> {
    /** 消息唯一ID（幂等键），UUID 或 雪花 */
    private String msgId;
    /** 业务类型：SECKILL_ORDER / BLOG_PUBLISH / ... */
    private String bizType;
    /** 业务幂等键：秒杀=orderId，点赞=blogId:userId，发博客=blogId */
    private String bizKey;
    /** 链路追踪 */
    private String traceId;
    /** 产生时间（毫秒） */
    private Long timestamp;
    /** 重试次数（由消费者回写，便于排查） */
    private Integer retryCount;
    /** 业务载荷 */
    private T payload;
}
```

**为什么必须有 `bizKey`**：这是消费端幂等的基础。RocketMQ "至少一次"投递，重试/重复投递是常态，不能靠 `msgId`（重试时不变但可能换 msgId），要用**业务语义键**。

### 2.3 公共组件（一次写好，7 个 Topic 复用）

```
com.hmdp.mq
├── config/
│   ├── RocketMqProperties.java        # topic/group/线程数/重试次数 配置绑定
│   └── RocketMqAutoConfig.java        # Producer + 各 Consumer Container Bean
├── core/
│   ├── MqMessage.java                 # 信封
│   ├── MqTemplate.java                # 统一发送：同步/异步/延迟/顺序 + 自动填信封
│   ├── MqIdempotentService.java       # 幂等：Redis SETNX + 本地锁双检
│   ├── MqConsumerSupport.java         # 抽象消费模板：反序列化/幂等/trace/异常语义/指标
│   └── OutboxService.java             # 本地消息表（Redis ZSet + DB 双写可选）
├── producer/
├── consumer/
└── message/                           # 各业务 payload DTO
```

**`MqConsumerSupport` 是这个设计的核心**——把所有消费者共同的横切逻辑收口：

```java
public abstract class MqConsumerSupport<T> implements RocketMQListener<MessageExt> {

    @Override
    public final void onMessage(MessageExt raw) {
        MqMessage<T> msg = deserialize(raw);
        String traceId = MDC.get("traceId");
        MDC.put("traceId", msg.getTraceId());
        try {
            // ① 幂等：已处理过直接 ACK
            if (!idempotent.tryLock(msg.getBizKey(), Duration.ofMinutes(10))) {
                log.info("重复消息，跳过 bizKey={}", msg.getBizKey());
                return;
            }
            // ② 业务处理
            doConsume(msg.getPayload());
            // ③ 标记成功（短期保留，防重投）
            idempotent.markDone(msg.getBizKey(), Duration.ofMinutes(10));
            counter("mq.consume.success", "topic", topic()).increment();
        } catch (BizFinalException e) {
            // 业务终态：ACK，不重试
            log.warn("业务终态丢弃 bizKey={} reason={}", msg.getBizKey(), e.getMessage());
            counter("mq.consume.final", "topic", topic()).increment();
        } catch (Exception e) {
            counter("mq.consume.retry", "topic", topic()).increment();
            throw e;   // ★ 抛出才会重试
        } finally {
            MDC.remove("traceId");
        }
    }

    protected abstract void doConsume(T payload);
    protected abstract String topic();
}
```

### 2.4 幂等方案（三层）

```
第 1 层：Redis SETNX  bizKey → 防并发重复消费（速度快，10 分钟窗口）
第 2 层：DB 唯一索引/业务查重 → 最终防线（永久有效）
第 3 层：（可选）幂等表 mq_consume_log → 审计与排障
```

> 与秒杀文档一致：**MQ 的"至少一次"必须靠业务幂等兜住**，这是迁移 MQ 最容易漏的一环。

### 2.5 依赖与配置

```xml
<dependency>
    <groupId>org.apache.rocketmq</groupId>
    <artifactId>rocketmq-spring-boot-starter</artifactId>
    <version>2.3.1</version>   <!-- 2.3.x 才适配 Spring Boot 3 / jakarta -->
</dependency>
```

```yaml
rocketmq:
  name-server: ${ROCKETMQ_NAMESERVER:127.0.0.1:9876}
  producer:
    group: hmdp-producer
    send-message-timeout: 3000
    retry-times-when-send-failed: 1

hmdp:
  mq:
    # 全局开关：可一键降级回"同步执行"（见 §6）
    enabled: ${HMDP_MQ_ENABLED:true}
    seckill:
      topic: hmdp-seckill-order
      consumer-group: hmdp-seckill-order-consumer
      threads: 8
      max-reconsume: 3
    blog-publish:
      topic: hmdp-blog-publish
      consumer-group: hmdp-blog-publish-consumer
      threads: 8
      max-reconsume: 5
      # 单批 fan-out 上限，防止大 V 撑爆内存
      fanout-batch-size: 500
```

---

## 3. P1 · 事件类改造（1.5 人日）—— 先建地基

> 目标：用**风险最低**的场景把 MQ 基础设施跑顺，形成规范，再动 Feed 和秒杀。

### 3.1 点赞事件（`hmdp-blog-like`）

**现在**：`likeBlog:110-152` 在 Redisson 锁内做「查状态 + 改 DB + 改 ZSet」，锁租约只有 3 秒，DB 一慢就超时。

**改造后**：

```java
public Result likeBlog(Long id) {
    // 锁内只做"状态判定 + 写 Redis"，DB 与衍生动作用 MQ 异步
    // → 锁持有时间从 ~5ms 降到 ~0.3ms
    ...
    mqTemplate.send("hmdp-blog-like",
        MqMessage.of("BLOG_LIKE", id + ":" + userId,
            new BlogLikeEvent(id, userId, liked, System.currentTimeMillis())));
    return Result.ok();
}
```

**消费者做三件事**（都可以独立失败重试）：
1. `UPDATE tb_blog SET liked = liked ± 1`（**幂等**：用 `liked = (SELECT COUNT(*) FROM tb_blog_like WHERE blog_id=?)` 或事件去重表）
2. 更新点赞排行榜/热度 ZSet
3. 给博主发通知（`hmdp-notify`）

> ⚠️ 注意：`likeBlog` 已有的 `if (success)` 守卫不可丢（`50d6092` 修过的 bug），改造时必须保留"DB 成功才写 Redis"的因果。

### 3.2 评论事件（`hmdp-blog-comment`）— 顺带补功能

`BlogCommentsServiceImpl` 现在是空的，P1 顺手补齐：

```
POST /blog-comments
  → save(comment)                          // DB 落库（同步，用户要立刻看到）
  → mqTemplate.send(hmdp-blog-comment, {commentId, blogId, authorId, content摘要})
      → 消费者1：tb_blog.comments + 1
      → 消费者2：发通知给博主（hmdp-notify）
```

### 3.3 关注事件（`hmdp-follow`）

```
PUT /follow/{id}/{isFollow}
  → DB 写 tb_follow + Redis Set（同步，需立即生效）
  → mqTemplate.send(hmdp-follow, {fromUserId, toUserId, follow:true/false})
      → 消费者1：维护粉丝数（Redis INCR）
      → 消费者2：关注时把该博主最近 N 条博客补推到我的收件箱（解决"关注后 Feed 为空"）
```

> 第 2 件事是**当前真实缺陷**：现在关注一个新博主后，收件箱只有他**之后**发的博客，历史内容看不到。这是产品级体验问题，用 MQ 异步补推最自然。

**P1 验收**：
- [ ] 三类事件都能正常投递与消费
- [ ] 手工重发同一 `bizKey` 3 次，业务只生效 1 次（幂等生效）
- [ ] 停掉 Broker，发博客/点赞**接口不报错**（异步降级，见 §6）
- [ ] `%DLQ%` 能收到人为构造的失败消息

---

## 4. P2 · Feed 推送改造（2 人日）—— 主收益

### 4.1 目标链路

```
POST /blog                                  （主线程 ~20ms 返回）
  └─ VoucherOrderServiceImpl 外的 BlogServiceImpl.saveBlog()
       ├─ save(blog)                        DB 落库（事务内）
       ├─ mqTemplate.send("hmdp-blog-publish",
       │      MqMessage.of("BLOG_PUBLISH", blogId, new BlogPublishEvent(blogId, authorId, createTime)))
       └─ return blogId                     ★ 不再 for 循环

                         ↓ Topic: hmdp-blog-publish

FeedFanoutConsumer（8 线程并发）
  ├─ 查粉丝列表（分页，batch=500）
  ├─ 批量 ZADD：pipeline 一次提交 500 条
  ├─ 单页失败只重试该页（不是整条消息）
  └─ 全部完成后 ACK

                         ↓ 粉丝读
GET /blog/of/follow  →  不变（读自己的收件箱 ZSet，双游标分页）
```

### 4.2 关键实现

```java
@Component
@Slf4j
public class FeedFanoutConsumer extends MqConsumerSupport<BlogPublishEvent> {

    private static final int BATCH = 500;

    @Override
    protected void doConsume(BlogPublishEvent event) {
        long lastId = 0L;
        int total = 0;
        while (true) {
            // ① 分页查粉丝（游标翻页，避免 offset 深翻）
            List<Follow> fans = followMapper.selectByFollowUserIdAfter(
                    event.getAuthorId(), lastId, BATCH);
            if (CollectionUtil.isEmpty(fans)) break;

            // ② 按 500 一批 pipeline 写 ZSet
            stringRedisTemplate.executePipelined((RedisCallback<Object>) conn -> {
                for (Follow fan : fans) {
                    byte[] key = (FEED_KEY + fan.getUserId()).getBytes(UTF_8);
                    conn.zSetCommands().zAdd(key,
                            event.getCreateTime(),
                            event.getBlogId().toString().getBytes(UTF_8));
                }
                return null;
            });

            lastId = fans.get(fans.size() - 1).getId();
            total += fans.size();
            log.debug("Feed 推送到 {} 人，累计 {}", fans.size(), total);
        }
        counter("feed.fanout.users").increment(total);
    }
}
```

**三个技术点**：

| 点 | 为什么 |
|---|---|
| **pipeline 批量写** | 1 万粉丝 = 1 万次 RTT（≈2s）→ 20 批 pipeline（≈50ms） |
| **游标翻页查粉丝** | `LIMIT offset,500` 深翻页会越来越慢；用 `id > lastId` 游标 |
| **分批幂等** | 重试时整条消息重跑，`ZADD` 本身幂等（同 member 同 score），无需额外去重 |

### 4.3 必须补的索引（否则 MQ 也救不了）

```sql
ALTER TABLE tb_follow ADD INDEX idx_follow_user_id (follow_user_id, id);
```

现在 `tb_follow` **只有主键**，`where follow_user_id = ?` 是全表扫描。粉丝 10 万时这条查询就 1 秒以上 —— MQ 只是把耗时从"用户等待"搬到"消费者线程"，不加索引会变成消息堆积。

### 4.4 大 V 特殊处理（threshold）

写扩散的经典问题是"大 V 发一条博客要推给 100 万人"：

```yaml
hmdp:
  mq:
    blog-publish:
      # 粉丝数超过该阈值，走"拉模式混合"：不推送到收件箱，
      # 读 Feed 时额外合并大 V 的最新内容
      big-v-threshold: 100000
```

```
粉丝数 < 10万 → 推模式（写扩散，读快）
粉丝数 ≥ 10万 → 不推，读时从 tb_blog 按作者拉取并归并（读扩散）
```

> 这是**推拉结合**方案。学习项目可以先不做，但要在文档里留出扩展点——面试被问"大 V 怎么办"时能答上来。

### 4.5 收件箱清理（现在没有，会无限增长）

```java
// 写入时截断，只保留最近 1000 条
redisTemplate.opsForZSet().removeRange(key, 0, -1001);
// 或统一 TTL
redisTemplate.expire(key, Duration.ofDays(30));
```

**P2 验收**：
- [ ] 发博客接口 P99 < 50ms（改造前粉丝 1000 人时 > 500ms）
- [ ] 1000 粉丝 → 收件箱 5 秒内全部写入
- [ ] 消费者中途抛异常 → 重试后收件箱数据正确（`ZADD` 幂等）
- [ ] 停 Broker → 发博客仍成功，Broker 恢复后 Feed 补齐
- [ ] 收件箱不会无限增长（截断/TTL 生效）

---

## 5. P3 · 秒杀改造（2 人日）—— 复用前面能力

P3 的完整设计见 `rocketmq-seckill-design.md`（请求账本 + Outbox + 预占/释放协议 + 对账；`redis-stream-to-rocketmq-migration.md` 是其原子性前置分析）。这里只列出**与 P1/P2 的复用关系**：

| 秒杀需要的 | 从哪来 |
|---|---|
| 消息信封 `MqMessage` | P1 已建 |
| 幂等 `MqIdempotentService` | P1 已建 |
| 消费模板 `MqConsumerSupport` | P1 已建 |
| 异常语义（终态 vs 可重试） | P1 已建 |
| 轻量补偿表（Redis ZSet + Relay） | P1/P2 事件类使用，best-effort |
| **MySQL Outbox + 租约 + 对账** | P3 独有，按秒杀设计文档新建，**不复用**轻量补偿表 |
| **原子性处理**（Lua 扣减 + 消息投递） | P3 独有，最难 |

**顺序收益**：等做到 P3 时，团队/你自己已经用 MQ 处理了 6 个 Topic，异常语义、幂等、监控都成了肌肉记忆，再去碰"有钱相关"的秒杀链路，风险大幅降低。

### 5.1 秒杀与博客的统一抽象

```java
// 一个发消息的统一入口，所有业务共用
@Service
public class MqTemplate {
    public <T> void send(String topic, MqMessage<T> msg);              // 同步
    public <T> void sendAsync(String topic, MqMessage<T> msg);          // 异步（Feed/点赞用）
    public <T> void sendDelay(String topic, MqMessage<T> msg, Duration delay); // 延迟（关单用）
    public <T> void sendOrderly(String topic, MqMessage<T> msg, String shardingKey); // 顺序
}
```

**选择原则**：

| 场景 | 发送方式 | 理由 |
|---|---|---|
| 秒杀下单（Outbox 发布任务） | **同步** `send` | 由发布任务在**事务外**发送并等待确认；失败只更新退避时间重投，**不据此回滚库存**（秒杀设计文档规则 7） |
| Feed 推送 | `sendAsync` | 失败可容忍，不阻塞用户 |
| 点赞/评论事件 | `sendAsync` | 同上 |
| 订单超时关单 | `sendDelay` | 15 分钟后触发 |
| 点赞计数（同 blogId） | `sendOrderly` + shardingKey=blogId | 避免 +1/-1 乱序导致计数错乱 |

### 5.2 可靠性分级：同一套基建，两档可靠性

**分级原则：按"消息丢失/重复的业务后果"选档位，不按场景难度堆料。**

| | 轻量档（P1/P2：点赞/评论/关注/Feed） | 完整档（P3：秒杀订单） |
|---|---|---|
| 丢失后果 | 粉丝晚几秒看到动态、计数短暂偏差 | 丢消息=丢单；重复=超卖/重复下单 |
| 投递保障 | at-least-once + Broker 重试 | MySQL 请求账本 + Outbox 租约（秒杀设计文档） |
| 幂等手段 | **天然幂等**：收件箱 `ZADD`（同 member 同 score）、计数用状态重算/事件去重表 | 请求行锁 + 状态机 + `UNIQUE(user_id, voucher_id)` 兜底 |
| 失败补偿 | Redis 补偿表 + Relay 补发（尽力而为） | Outbox 重投 + 对账扫描 + 按 orderId 幂等释放 |
| 停机语义 | 降级同步执行或延后生效，用户可容忍 | 拒绝新预占，**绝不静默降级**为无保护的直接落库 |

> 判断口诀：**重试后结果会变吗？丢了赔钱吗？** 两个都"否"走轻量档；任一为"是"必须完整档。
> 反过来说：给博客 Feed 上请求账本和对账表是过度设计——收敛慢、维护贵，面试也讲不出必然性。
> 轻量档与完整档共用 P0 的信封、发送模板、消费模板和幂等组件；差异只在于"是否持久化受理事实、是否对账"。

---

## 6. 降级与开关（比迁移本身更重要）

**核心原则：MQ 挂了，业务不能挂。**

```java
public <T> void sendAsync(String topic, MqMessage<T> msg) {
    if (!mqProperties.isEnabled()) {                    // ① 全局开关关闭
        log.warn("MQ 已禁用，丢弃消息 topic={}", topic);
        return;
    }
    try {
        rocketMQTemplate.asyncSend(topic, msg, callback, 3000);
    } catch (Exception e) {
        // ② 发送失败：写本地补偿表，由定时任务补发
        outboxService.save(topic, msg);
        meterRegistry.counter("mq.send.fail.fallback", "topic", topic).increment();
        log.error("MQ 发送失败，已写入本地补偿 topic={}", topic, e);
    }
}
```

**分级降级策略**：

| 场景 | MQ 不可用时的行为 | 用户感知 |
|---|---|---|
| Feed 推送 | 写入 Redis 补偿 ZSet，Relay 恢复后补推 | 延迟看到动态（可接受） |
| 点赞 | 同步兜底执行（这次慢一点） | 稍慢 |
| 评论 | 同步兜底 | 稍慢 |
| **秒杀** | 受理照常成功（Outbox 已随事务落库），发布任务退避重投；仅 Redis 不可用时拒绝新预占 | "抢到了，结果稍后可查"（结果查询接口闭环，见秒杀设计文档 §8） |

---

## 7. 可观测

| 指标 | 类型 | 用途 |
|---|---|---|
| `mq.send.success/fail{topic}` | Counter | 发送健康度 |
| `mq.consume.success/retry/final{topic}` | Counter | 消费健康度 |
| `mq.consume.duration{topic}` | Timer | P99 耗时，发现慢消费 |
| `feed.fanout.users` | Counter | 推送量 |
| `feed.fanout.batch.duration` | Timer | 单批写入耗时 |
| RocketMQ 原生 | Dashboard | 堆积量、TPS、重试、DLQ |

**告警阈值**（配合 `actuator` 一起做）：
- 消费堆积 > 10000 → 告警
- `%DLQ%` 有消息 → 立即告警
- `mq.send.fail` 5 分钟内 > 10 → 告警
- `feed.fanout` P99 > 5s → 告警（粉丝数异常或 Redis 慢）

---

## 8. 测试矩阵

| # | 场景 | 期望 |
|---|---|---|
| 1 | 发博客，1000 粉丝 | 接口 < 50ms；5s 内 1000 个收件箱各 1 条 |
| 2 | 发博客，Broker 停机 | 接口成功；Broker 恢复后 Feed 补齐（补偿表生效） |
| 3 | 同一博客消息重投 5 次 | 收件箱不重复（ZADD 幂等） |
| 4 | 点赞消息重复消费 | `liked` 计数正确（不重复 +1） |
| 5 | 点赞消息乱序（+1 在 -1 之后到） | 最终计数正确（顺序消费或状态重算） |
| 6 | 关注新博主 | 历史博客补推成功，Feed 不再为空 |
| 7 | 消费端持续抛异常 | 进 `%RETRY%`，5 次后进 `%DLQ%`，告警触发 |
| 8 | 秒杀 500 并发 | 无超卖、无重复单（唯一索引 + 幂等） |
| 9 | 秒杀时 Broker 停机 | 受理成功返回 orderId，Outbox 待发；Broker 恢复后自动补投、订单正常创建（库存不回滚） |
| 10 | 大 V（>10万粉丝）发博客 | 走拉模式分支，接口仍 < 50ms |

---

## 9. 排期与里程碑

| 阶段 | 内容 | 工期 | 里程碑验收 |
|---|---|---|---|
| **P0** | 依赖/配置/Topic 规划/`MqMessage`/`MqTemplate`/`MqConsumerSupport`/幂等组件/dmq docker-compose | 1.5 人日 | 一个 Demo Topic 打通收发 |
| **P1** | 点赞、评论（补功能）、关注三类事件 | 1.5 人日 | 三类事件跑通 + 幂等测试通过 |
| **P2** | Feed 推送（分页+pipeline+大V阈值+收件箱清理）+ `tb_follow` 索引 | 2 人日 | 发博客 P99 < 50ms |
| **P3** | 秒杀迁移（含 Outbox）+ 超时关单延迟消息 | 2.5 人日 | 无超卖、Broker 停机不丢单（Outbox 补投） |
| **P4** | 可观测/告警/压测/文档 | 1.5 人日 | Dashboard + 压测报告 |
| **合计** | | **9 人日** | |

### 9.1 关键路径

```
P0 基础设施 ──┬─→ P1 事件（低风险验证）
              │
              ├─→ P2 Feed（主收益）
              │
              └─→ P3 秒杀（依赖 P1 的幂等 + 异常语义）
                        │
                        └─→ P4 可观测
```

**P0 是所有阶段的硬依赖**，先把信封、模板、幂等、消费支持写好，后面 7 个 Topic 都是"填业务逻辑"，不会再碰基础设施。

### 9.2 如果时间只有 3 天

做 **P0 + P2**：
- 得到"发博客不再卡死"这个最直观的收益
- Feed 推送是纯新增、无回滚风险
- 秒杀保持 Stream 不动（它已经能用，只是不够强）

这比"三个都做一半"强得多。

---

## 10. 与 `hmdp-plus` 的对照（避免重复踩坑）

`hmdp-plus` 也用了 Kafka（同类 MQ），它的经验与教训：

| Plus 的做法 | 我们怎么做 | 原因 |
|---|---|---|
| `acks=all` + 幂等生产者 | ✅ 照做 | 基础可靠性 |
| 发送失败回滚 Redis | ❌ **不照做** | 秒杀设计文档规则 7：发送失败/超时≠业务取消，走 Outbox 重投，不释放库存 |
| 指数退避 + DLQ | ✅ 做，但**只做一层** | Plus 里只有缓存失效链路有，不统一 |
| **声称有 Outbox 但没实现** | ✅ **真做**（Redis ZSet） | 这是 Plus 最大的"名不副实" |
| 消费超时 > 10s 直接丢弃 | ❌ **不照做** | 丢单风险，应改为重试 + 死信 |
| `createVoucherOrder` 查重 | ✅ 照做 + **唯一索引** | Plus 只有应用层查重 |
| 启动时清空 Redis 已购集合 | ❌ 绝不 | Plus 这个做法让一人一单在 Redis 侧失效 |
| 9 个模块的框架化 | ❌ 不做 | 单模块 + `mq` 包足够 |

### 10.1 另一个独立参照：小红书分享的魔改工程（2026-10 调研）

来源为小红书笔记（黑马点评魔改增强版分享），源码未公开，以下按笔记描述对照，仅作设计佐证、无可抄代码：

| 该工程的做法 | 本计划 | 说明 |
|---|---|---|
| 券/订单链路：RocketMQ + Outbox/**Inbox**、事务保存任务、租约认领、失败重投、消费去重；另有普通领券幂等、订单快照、超时关单、条件状态更新 | 与 P3 + `rocketmq-seckill-design.md` 高度收敛 | 其"持久预约、pending 任务接管、幂等补偿、库存对账、防误释放新订单资格"与秒杀设计文档逐条对应——独立实现收敛，可作为方案组件必要性的旁证 |
| 秒杀投递通道：**保留 Redis Stream** + 自建 pending 接管 | P3 移除 Stream，RocketMQ 直接投递 | 保留 Stream 就得自建接管逻辑，并不省事；秒杀设计文档 §1 已论证取舍 |
| Kafka 仅用于行为采集 + 商家热榜 | 不引入 Kafka 双栈 | 热榜若做，用已引入的 RocketMQ 普通消息即可 |
| **博客域完全未 MQ 化** | P1/P2 是本文主体 | 我们的差异化：Feed 推送是"同步改异步"的纯收益场景，且按 §5.2 走轻量档即可 |
| 缓存：请求内/Caffeine/Memcache 多层 + 版本栅栏 + Canal 同步 | 本文范围外 | 版本栅栏（防旧值写回）与 Canal（删缓存失败兜底）值得**单独立项**，不混入 MQ 计划 |
| 桌面体验页：缓存命中路径、事件持久回执、订单任务进度可视化 | P4 可参考 | 把 §7 的指标做成只读页面，同时服务验收观察与演示 |

---

## 11. 风险与对策

| # | 风险 | 概率 | 对策 |
|---|---|---|---|
| 1 | `rocketmq-spring-boot-starter` 与 Boot 3.5.9 兼容问题 | 中 | P0 第一天先用最小 Demo 验证；锁定 2.3.x |
| 2 | 本地起 NameServer+Broker 吃内存 | 高 | docker-compose + Broker `-Xms512m -Xmx512m` |
| 3 | 大 V 发博客导致单条消息处理超长 | 中 | 分页 + pipeline + 大V阈值走拉模式 |
| 4 | 幂等没做好导致点赞数错乱 | 高 | 三层幂等 + 测试用例 4/5 |
| 5 | 秒杀改坏（丢单/超卖） | 中 | 最后做；保留 Stream 代码 + 配置开关回滚 |
| 6 | 以为用了 MQ 就高可用 | 高 | Broker 也要主从；单机 Broker 一样丢消息 |
| 7 | Feed 收件箱无限增长 | 高 | 写入时 `ZREMRANGEBYRANK` 截断 + TTL |
| 8 | 加了索引但没验证 | 中 | `EXPLAIN` 确认 `idx_follow_user_id` 生效 |

---

## 12. 面试怎么讲

**一句话**：
> 我把项目里所有异步场景统一到 RocketMQ：先用点赞/评论/关注这类低风险事件把消息信封、幂等、消费模板这套基础设施跑顺，再用它改造博客 Feed 推送（原来同步 for 循环写 1 万粉丝要 1-3 秒，改造后接口 20ms、消费者 pipeline 批量写），最后才迁移秒杀下单——因为秒杀涉及"Lua 原子扣减 + 消息投递"的原子性断裂，风险最高，放在基础设施成熟后做。

**三个可深挖点**：

1. **"为什么博客推送比秒杀更适合先做？"**
   → 秒杀是"替换已有 MQ + 破坏原子性"（高风险改造）；博客是"同步改异步"（纯收益、无原子性冲突）。风险排序决定实施顺序。

2. **"1 万粉丝怎么推？"**
   → 分页游标查粉丝（避免深翻页）+ pipeline 批量 ZADD（1 万次 RTT → 20 批）+ 大 V 阈值走拉模式 + 收件箱截断。给出改造前后 P99 对比数据。

3. **"MQ 挂了怎么办？"**
   → 分级降级：Feed/点赞/评论走本地补偿表 + Relay 补发（用户无感）；秒杀受理照常成功，靠 Outbox 重投闭环，不回滚库存。

4. **"博客推送和秒杀都用 MQ，可靠性设计一样吗？"**
   → 不一样，按业务后果分级（§5.2）：博客 Feed 丢一条可容忍，at-least-once + ZADD 天然幂等 + 轻量补偿表即可；秒杀丢一条就是丢单，必须请求账本 + Outbox 租约 + 对账闭环。同一套基建、两档可靠性，既避免过度设计也避免设计不足。

---

## 附录 A · 新增/改动文件清单

| 文件 | 动作 |
|---|---|
| `pom.xml` | + `rocketmq-spring-boot-starter` 2.3.1 |
| `application.yaml` | + `rocketmq.*` + `hmdp.mq.*` |
| `docker-compose.yml` | **新增** nameserver + broker |
| `mq/config/RocketMqProperties.java` | **新增** |
| `mq/config/RocketMqAutoConfig.java` | **新增** |
| `mq/core/MqMessage.java` | **新增** 信封 |
| `mq/core/MqTemplate.java` | **新增** 统一发送 |
| `mq/core/MqIdempotentService.java` | **新增** 幂等 |
| `mq/core/MqConsumerSupport.java` | **新增** 消费模板 |
| `mq/core/OutboxService.java` | **新增** 补偿表 |
| `mq/message/*.java` | **新增** 各事件 DTO |
| `mq/consumer/FeedFanoutConsumer.java` | **新增** Feed 推送 |
| `mq/consumer/BlogLikeConsumer.java` | **新增** |
| `mq/consumer/BlogCommentConsumer.java` | **新增** |
| `mq/consumer/FollowConsumer.java` | **新增** |
| `mq/consumer/SeckillOrderConsumer.java` | **新增**（P3） |
| `service/impl/BlogServiceImpl.java` | 改 `saveBlog`（去 for 循环）、`likeBlog`（DB 改异步） |
| `service/impl/BlogCommentsServiceImpl.java` | **补实现**（当前为空） |
| `service/impl/FollowServiceImpl.java` | 加事件投递 |
| `service/impl/VoucherOrderServiceImpl.java` | 拆分为受理/建单/关闭/恢复服务，删 Stream 消费器（P3，详见秒杀设计文档 §10） |
| `resources/lua/seckill/*.lua` | 重写为预占/确认/释放脚本族，带代次与 hash tag（P3，现脚本无时间窗校验需一并补） |
| `resources/db/hmdp.sql` | + `tb_follow(follow_user_id, id)` 索引；+ 秒杀唯一索引 |
| `enums/MqBizType.java` | **新增** |

## 附录 B · 一页速查

```
能上 MQ 的场景（按收益排序）
  1. 博客 Feed 推送   同步 for 循环 → 异步 fan-out   ★★★ 收益最大、风险最低
  2. 点赞/评论/关注    事件解耦                       ★★  顺带补评论功能
  3. 秒杀下单         替换 Stream                     ★   风险最高、最后做
  4. 订单超时关单     延迟消息                        ★★  新功能
  5. 缓存失效广播     Pub/Sub 更合适，不用 MQ          —

必须统一的四件套（P0）
  信封 MqMessage（含 bizKey 幂等键）
  发送 MqTemplate（sync/async/delay/orderly）
  消费 MqConsumerSupport（幂等 + 异常语义 + 指标）
  补偿 OutboxService（MQ 挂了不丢消息）

三条铁律
  ① 重试后结果会变吗？会 → 抛异常重试；不会 → 正常返回 ACK
  ② 所有消费必须幂等（MQ 是"至少一次"）
  ③ MQ 挂了业务不能挂（分级降级）

可靠性分两档（§5.2）
  轻量档（点赞/评论/关注/Feed）：at-least-once + 天然幂等 + Redis 补偿表
  完整档（秒杀订单）：请求账本 + Outbox 租约 + 对账（rocketmq-seckill-design.md）

不做
  ❌ 9 模块框架化（Plus 的坑）
  ❌ 消费超时直接丢消息（Plus 的坑）
  ❌ 启动清空 Redis 已购集合（Plus 的坑）
```
