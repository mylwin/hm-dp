# 订单 RocketMQ 第二阶段：数据设定、事务级别与并发边界

核对日期：2026-10-24。本文说明当前工作区三表版本的设定与取舍，只补文档，不调整 Java、Lua、SQL、数据库参数或组件开关。
配合 [阶段实现说明](ROCKETMQ_PHASE2.md)、[架构与表职责复盘](ROCKETMQ_PHASE2_REVIEW.md) 阅读。

## 1. 先区分四个不同的问题

| 问题 | 当前依靠什么 | 不能指望什么 |
|---|---|---|
| 同一 MySQL 事务内的 SQL 是否一起提交 | InnoDB + TransactionTemplate | 隔离级别不决定 Redis/MQ 是否跟着回滚 |
| 并发请求看到哪个版本、怎样竞争 | READ_COMMITTED + 明确的锁定读 | RC 本身不等于串行执行、不自动防重复 |
| 重复请求、重复事件能否重复登记 | 主键、业务唯一键、参数绑定、条件更新 | Java 先查一次“没有”不能阻止另一个线程同时插入 |
| Redis/MySQL/MQ 之间失败后怎样恢复 | PREPARING、稳定订单号、Outbox、后续恢复协议 | 本地事务不能把三个系统变成同一个原子事务 |

因此要分别审阅事务边界、隔离级别、传播行为和业务约束，不能把它们统称为“加了事务就安全”。

## 2. 当前参数总表及核对范围

### 2.1 代码显式设定

| 项目 | 当前值 | 设置位置 / 目的 |
|---|---|---|
| 新表引擎 | InnoDB | 迁移 SQL；支持数据库事务、行锁和唯一约束 |
| 新短事务传播行为 | REQUIRES_NEW | 两个 transaction() 工厂；每次 execute 使用独立事务边界 |
| 新短事务隔离级别 | READ_COMMITTED | 两个 transaction() 工厂；新事务普通读使用语句级已提交视图 |
| 受理事务超时 | 默认 5 秒，可设 1～30 秒 | SeckillOrderProperties；作用于请求登记、受理这两次 execute |
| 投递领取/回执事务超时 | 固定 5 秒 | OrderOutboxDispatcher.transaction() |
| 新 Mapper SQL 超时 | 5 秒 | @Options(timeout=5)；与剩余事务预算共同影响实际语句超时 |
| 活动读取 | FOR SHARE NOWAIT | 保持活动维护共享锁，冲突不排队等待该行 |
| 请求读取 | FOR UPDATE NOWAIT | 同一请求只允许一个事务推进 |
| Outbox 领取 | FOR UPDATE SKIP LOCKED，LIMIT 1 | 跳过其他领取者持有的行锁，逐条领取 |
| 默认租约 | 30 秒，可设 1～300 秒 | lease_until，发送后的状态更新还要校验 token 与有效期 |
| 扫描 | 每轮最多 20 条，上轮完成后延迟 1 秒 | 不是固定每秒保证处理 20 条，也不是一次领 20 条 |
| Redis 连接/命令超时 | 2 秒 / 3 秒 | application.yaml；命令超时给旧 Stream BLOCK 2000ms 留余量 |
| MQ 发送超时 / SDK 额外重试 | 默认 3 秒 / 0 次 | 第一阶段配置；持久化重试由 Outbox 承担 |
| 连接池 | Druid 最大 20，取连接最长等待配置为 5 秒 | 全应用共享，不是订单模块独享 |
| JDBC 网络参数 | connectTimeout=3000，socketTimeout=60000 | 单位毫秒；与事务/语句超时是不同层的控制 |
| 字符集 / 排序规则 | utf8mb4 / utf8mb4_bin | 三张新表，支持 Unicode 并区分标识大小写 |
| 时间字段 | DATETIME(3)，约定保存 UTC | 毫秒精度；租约 SQL 统一用数据库 UTC_TIMESTAMP(3) |

### 2.2 本地 MySQL 实际读取值

对当前 Compose MySQL 执行只读查询，得到：

| 项目 | 2026-10-24 读取值 | 如何理解 |
|---|---|---|
| MySQL 版本 | 8.4.11 | 是本地数据库版本，不是虚拟机 RocketMQ 版本 |
| GLOBAL transaction_isolation | REPEATABLE-READ | 服务器默认，未被本阶段全局修改 |
| 本次 CLI SESSION transaction_isolation | REPEATABLE-READ | 新建 CLI 连接继承默认值，不能代表程序事务内部 |
| 本次 CLI SESSION autocommit | 1 | 不表示 Spring 事务中的写入也逐条自动提交 |
| innodb_flush_log_at_trx_commit | 1 | 当前 redo 提交刷盘配置，不等于具备异机容灾 |
| sync_binlog | 1 | 当前 binlog 同步配置，不等于已经部署同步副本 |
| binlog_format | ROW | 与本方案 RC 及队列领取语句的使用方向一致 |
| innodb_lock_wait_timeout | 50 秒 | 行锁等待的服务器默认；NOWAIT 和 JDBC 超时另有作用 |
| 本次 CLI SESSION time_zone | SYSTEM | 会话默认时区，不是“所有时间列自动存 UTC”的证明 |

这些是此次读取结果，不是永久不变的设计保证，也没有据此修改服务器设置。

## 3. 为什么用 READ_COMMITTED

当前两个事务工厂都显式调用：

```java
template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
```

### 3.1 RC 与 RR 对普通读取的区别

| 场景 | READ_COMMITTED（RC，读已提交） | REPEATABLE_READ（RR，可重复读） |
|---|---|---|
| 普通一致性 SELECT | 每条语句读取新的已提交快照 | 同一事务通常沿用首次一致性读建立的快照 |
| 别人未提交的修改 | 不作为普通已提交结果读取 | 同样不作为普通已提交结果读取 |
| 别人随后提交，当前事务再次普通 SELECT | 可以看到新的提交 | 通常继续读取原快照；自己的写入等情况需另看 |
| FOR UPDATE / FOR SHARE | 锁定读，不能按普通快照读解释 | 同样是锁定读，不是永远固定旧快照 |

示意：事务 A 第一次普通查询看到 PREPARING，事务 B 随后把它提交成 ACCEPTED。
如果 A 再执行普通查询，RC 与 RR 可能看到不同结果；如果 A 执行当前实现的 FOR UPDATE，必须按锁定读及锁竞争规则处理。
不要由这个示意得出“RR 读取不到自己写的数据”或“RR 一定实现不了当前协议”。

本项目短事务主要按稳定主键推进单条请求，后台任务则反复领取当前到期记录，不依赖长时间维持同一份业务快照。
选择 RC 是让这些普通读语义直接，并减少通常的范围间隙锁影响；它不是正确性的唯一来源。
RC 的搜索/扫描通常不使用间隙锁，但唯一键冲突、外键检查等仍有例外，死锁也仍可能发生。
这些行为见 [MySQL 8.4 隔离级别](https://dev.mysql.com/doc/refman/8.4/en/innodb-transaction-isolation-levels.html)。

### 3.2 为什么不把整个数据库改成 RC

显式设定仅作用于新 TransactionTemplate 创建的事务，现有业务没有因此统一改成 RC。
现有 @Transactional 若使用默认 isolation，并在没有其他外层事务的情况下启动，通常沿用数据源/会话默认隔离级别。
不应为这一条订单链路擅自改变商铺、用户及旧 Stream 事务的数据库全局行为。

`accept()` 最前面的 findByRequest 在这两个新模板之前执行，并没有单独声明 RC：

- 当前无外层事务调用时，它是独立的普通 Mapper 读取。
- 如果未来被一个已有事务的方法调用，这次前置读取可能参与调用方事务；后面的 REQUIRES_NEW 才会挂起外层事务。
- 当前受理服务没有主动禁止所有带事务的外层调用，不能把“建议从非事务入口调用”写成“代码已经强制检查”。

### 3.3 RC 为什么不等于防重复

两个线程可以同时普通查询，都得到“没有请求”。这在 RC 下完全可能，在 RR 下也不能靠先查后插就解决。
最终仍由 `UNIQUE(user_id, request_id)` 决定哪个请求记录成为有效记录。
同一请求状态的推进由行锁和 `WHERE status='PREPARING'` 共同保护；一个用户换 requestId 抢同一张券，则另靠 Redis 资格和未来订单唯一约束。

当前也没有选择 SERIALIZABLE：更严格的数据库隔离不能把 Redis 与 MQ 纳入本地提交，反而会增加不必要的竞争。
RR 并非不可用；若保留正确锁定读、唯一约束和恢复协议，也能设计正确方案，需要重新评估锁范围与查询语义。

## 4. 为什么用 REQUIRES_NEW，而不是给 accept 加一个大事务

### 4.1 四种事务边界

| 事务 | 包含的数据库操作 | 必须在什么时候结束 |
|---|---|---|
| T1 请求登记 | 插入/归并 PREPARING，并读取原请求 | Redis 预占之前就提交 |
| T2 请求受理 | 活动共享锁、请求行锁、更新 ACCEPTED/REJECTED、插入 Outbox | 返回已受理之前提交；其中调用 Redis，但没有 MQ 发送 |
| T3 领取任务 | 锁一条到期 Outbox，设置 SENDING、token、租约及尝试次数 | 发送 MQ 之前提交，释放数据库锁 |
| T4 保存结果 | 按有效 token 更新 SENT 或 PENDING 与下次重试时间 | 当前回执/重试状态持久化后结束 |

T1 与 T2 顺序执行；T3、T4 由后台调用，MQ 网络等待位于两者之间。它们不是四层嵌套事务。
T2 的 Lua 已预占但 SQL 失败时，只回滚 T2；T1 的 PREPARING 不能一起消失，否则失去恢复依据。
T2 中的 ACCEPTED 和 Outbox 必须一起提交，避免出现受理已成功却没有待发事件。

### 4.2 传播行为的准确含义

```java
template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
```

没有外层事务时创建新事务；有外层事务时挂起它，使用独立物理事务执行当前回调，结束后再恢复外层。
这让新事务可独立提交或回滚，隔离级别和超时也可以独立设置。[Spring 6.2 事务传播说明](https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/declarative/tx-propagation.html)

无外层事务时，两次顺序 REQUIRED execute 也可能分别创建并提交事务；不能说只有 REQUIRES_NEW 才能拆开两次执行。
当前选择 REQUIRES_NEW 是为了在出现外层事务时仍保留明确的独立提交语义。

| 选择 | 若调用方已有事务，会怎样 | 为什么当前没有采用 |
|---|---|---|
| 整个 accept 一个 REQUIRED 事务 | 前置登记与受理一起参与外层提交/回滚 | Lua 成功后回滚可能把恢复记录也回滚 |
| 两个 REQUIRED 模板 | 可能都加入已有外层事务 | 不能始终保证 T1 先独立提交 |
| NESTED 保存点 | 通常仍是同一物理事务的保存点 | 外层整体回滚仍会撤销之前的登记 |
| 当前 REQUIRES_NEW | 每次模板有自己的独立物理事务 | 独立提交清楚，但要管理连接资源和调用边界 |

### 4.3 连接池成本与调用约束

当没有外层事务时，这些短事务先后完成，通常不需要同时占两条事务连接。
如果调用方已有事务，它可能持有一条连接，REQUIRES_NEW 又申请另一条；高并发下可能耗尽当前最大 20 条连接池。
因此不能只看到“独立事务更安全”，忽略其资源成本，也不能把连接池机械扩大到某个倍数当成协议修复。

当前 Outbox 的 dispatchDue 显式拒绝在已有数据库事务的线程中调用。
否则 T3 独立提交后恢复外层事务，后面的 MQ 发送就仍可能落在外层事务生命周期里。
受理服务没有相同的入口禁用检查，后续接入时应优先从不包大事务的调用层进入，另行验证嵌套调用。

### 4.4 为什么用 TransactionTemplate

它把两个独立的提交点直接写在方法中，内部调用 private acceptLocked 也不依赖 Spring 代理拦截。
可以改用另一个 Bean 上的 @Transactional(REQUIRES_NEW) 方法，但不能简单给当前类的 private 方法加注解后通过 this 调用，就认为事务会自动生效。

框架 execute 的顺序是：启动事务 → 执行回调 → 提交 → 返回回调结果。
所以回调里构造 ACCEPTED 对象，不等于还没提交就已对外返回成功。

## 5. 各种锁到底保护什么

| SQL / 检查 | 保护的资源 | 当前作用 |
|---|---|---|
| 活动 FOR SHARE NOWAIT | 一张券的独立控制行 | 正常受理共享，未来维护排他；不把整张券的正常受理强制串行 |
| 请求 FOR UPDATE NOWAIT | 一个 orderId 的请求行 | 避免重复请求或未来关闭/恢复程序同时推进同一行 |
| Outbox FOR UPDATE SKIP LOCKED | 一条领取候选记录 | 多个领取者跳过已锁记录，领取事务结束后行锁释放 |
| token + lease_until 条件 UPDATE | 持久化任务的有效所有权 | 行锁已释放后仍防止旧发布者覆盖新结果 |
| 唯一索引冲突检查 | 数据库索引记录与相应冲突资源 | 最终兜底重复插入，不依赖应用普通 SELECT 的时机 |

### 5.1 为什么是“活动控制 → 请求”

同一事务按固定顺序取得资源，后续恢复、关闭、消费者和维护协议也应遵守一致顺序。
若 A 持请求锁再等活动锁，B 持活动锁再等请求锁，就可能形成循环等待。
当前共享控制行与真实库存行分离，也避免将来多个消费者先共享锁库存行、再同时升级写锁的问题。
本阶段只实现受理侧的顺序，不能宣称尚未实现的消费者/维护服务已经遵守该协议。

### 5.2 NOWAIT 与 SKIP LOCKED 的区别

NOWAIT 用于指定的活动/请求行，遇到行锁冲突直接报错；这里不能跳过请求 A，然后拿请求 B 当作 A 的结果。
SKIP LOCKED 用于后台候选集合，当前只需要找到某一条能处理的任务，可以先处理其他记录。
后者得到的不是完整一致快照，不能拿它统计全部待办或证明“系统没有剩余任务”。
两者只针对行级锁，不保证所有连接、元数据锁或网络操作都立即完成。[MySQL 锁定读说明](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html)

领取 Mapper 的 claim SQL 本身仅按 event_id 更新，它的安全前提是调用方已在同一个事务内执行 lockNextDue 并持有行锁。
不能把 claim 单独拿到事务外随意调用，再认为它已经自行实现完整的并发领取判断。

### 5.3 为什么仍不能承诺没有死锁

唯一索引插入、后续业务表写入或别的入口采用不同顺序，仍可能产生竞争或死锁。
NOWAIT 也没有覆盖 T1 的唯一键插入等待。收到锁冲突、死锁或超时时，需要保留稳定业务编号，由原请求重试或后续恢复处理。
当前已有指定并发用例，不等于已经完成全链路死锁、数据库宕机与大流量压力验收。

## 6. 唯一约束、条件更新与插入归并

### 6.1 三种唯一性的区别

| 约束 | 阻止什么 | 不阻止什么 |
|---|---|---|
| 请求主键 order_id | 同一编号重复登记两条请求 | 同用户换请求编号产生另一条请求 |
| UNIQUE(user_id, request_id) | 同用户同一次操作重复登记 | 同用户同券用不同 requestId 再次尝试 |
| Outbox 主键 event_id | 同事件编号重复登记 | 已登记事件向 Broker 重发 |
| UNIQUE(order_id, event_type) | 同订单同事件类型重复登记 | 不同类型事件或同一事件重复投递 |
| 未来订单 UNIQUE(user_id, voucher_id) | 同用户同券重复形成订单 | 当前尚未迁移该索引，不能计入已完成能力 |

### 6.2 为什么插入用 ON DUPLICATE KEY UPDATE

当前请求登记采用：

```sql
INSERT INTO tb_seckill_request (...) VALUES (...)
ON DUPLICATE KEY UPDATE order_id = order_id;
```

重复时保持已有记录，不覆盖原 orderId、参数、traceId、状态。随后按 userId + requestId 读取数据库中已经生效的记录。
例如 A、B 同时查不到请求，分别生成候选 1001、1002；A 先提交后，B 通过唯一键归并读取 1001，而不是将其改成 1002。
如果 A 回滚，B 才可能成功插入自己的候选；如果等待超时，需要重试，不能假装归并成功。

这个语句也会对其他唯一键冲突生效，所以订单号生成器异常导致主键撞到无关请求时，后续查询可能找不到对应 userId + requestId，
当前 requireNonNull 会失败，不会把无关请求当作当前用户请求继续预占。
影响行数与是否真正新插入不是一一对应，当前代码通过读取原记录确定有效业务编号。

### 6.3 为什么更新带状态条件

```sql
UPDATE tb_seckill_request
SET status = 'ACCEPTED', ...
WHERE order_id = ? AND status = 'PREPARING';
```

它明确限定允许的旧状态；requireOne 要求影响 1 行，否则异常退出并回滚当前事务。
这与行锁配合，不能在没有请求校验和业务分支的情况下，只靠一条 UPDATE 就声称实现了全部状态机约束。

Outbox 回执采用同一类条件更新，还增加当前 token 和有效期：

```sql
WHERE event_id = ? AND status = 'SENDING'
  AND lease_token = ? AND lease_until > UTC_TIMESTAMP(3)
```

更新 0 行表示不能覆盖当前状态，可能已被接管或租约过期，不等于应该重新扣库存或认定订单失败。

## 7. 回滚规则及跨系统边界

当前 callback 内发生 RuntimeException 或 Error，TransactionTemplate 会回滚；本地 Spring 6.2.15 源码也对意外 Throwable 做回滚处理。
不要直接把“声明式 @Transactional 默认 checked exception 不回滚”的简化结论套在这里。
当前 JSON 序列化异常会包装为 IllegalStateException，所以会触发受理事务回滚。

代码捕获事务二执行异常后，再包装 SeckillAcceptancePendingException：这个异常提供稳定订单号，表示要核查结果，
不保证数据库一定没提交。事务一执行前或执行中的异常不在同一个 catch 里，也不能把所有失败都描述成该自定义异常。

| 故障 | MySQL 事务效果 | Redis / MQ 效果 |
|---|---|---|
| T2 发生 SQL 或序列化异常 | T2 回滚，保留 T1 的请求登记 | 已执行的 Lua 不跟随回滚 |
| Lua 客户端等待超时 | T2 不确认受理，保留恢复依据 | 脚本可能已执行或稍后才执行 |
| T2 实际提交但响应丢失 | 账本和事件可能已经成功提交 | 原请求重试先查账本，不重复生成新请求 |
| MQ 已收但 T4 保存失败 | 领取记录仍可在租约到期后接管 | 原消息不能被数据库回滚撤销，可能重复投递 |

InnoDB 的事务原子性只覆盖同一数据库事务内的 SQL。
Redis Lua 保证执行期间不被其他 Redis 命令穿插，但脚本中途报错不会自动撤销已经执行的 Redis 写入。
因此预先检查类型/数值、保留原请求状态、稳定 orderId 和后续恢复协议都不能由数据库隔离级别代替。

## 8. 各层超时为什么不能混为一个数

| 超时 | 控制哪一层 | 为什么不足以单独限制整个请求 |
|---|---|---|
| Druid max-wait=5000ms | 取得数据库连接的等待 | 通常发生在拿到连接、登记事务超时预算之前 |
| 事务 timeout=5s | Spring JDBC 事务资源中的剩余时间预算 | 不是五秒后强制中断整个 Java 线程的定时器 |
| Mapper timeout=5s | JDBC Statement 查询执行 | 实际受事务剩余预算、JDBC 驱动及取消行为影响 |
| JDBC connectTimeout / socketTimeout | 数据库网络连接与读等待 | 与 SQL 语句取消、业务总时长不是同一层 |
| Redis connect=2s、command=3s | Redis 连接及命令等待 | 超时不证明 Lua 没执行，也不由 MySQL 自动撤销 |
| MQ send=3s | 一次发送等待 | 超时不证明 Broker 没接收 |
| Outbox lease=30s | 任务领取权 | 不负责中止旧线程、取消已发消息或回滚订单 |

本地 MyBatis 3.5.16 / MyBatis-Spring 3.0.4 源码会把事务剩余时间传给 Statement；在设置语句超时时采用更紧的剩余预算。
Spring JDBC 的事务超时主要落实到相应 JDBC 语句，而不是自动中断 Redis 调用。[Spring JDBC 事务说明](https://docs.spring.io/spring-framework/reference/6.2/data-access/jdbc/connections.html)

例如取得连接前等待 4 秒、拿到连接后再执行短事务，并不意味着用户总等待已经被严格限制为 5 秒。
同样，Debug 暂停超过预算后，通常会在后续 SQL 或资源检查处观察到超时，不能据此推断“IDEA 导致事务没生效”。
目前没有实现统一的端到端请求截止时间或数据库断连下的严格墙钟时间验收，不应把各个配置简单相加当成承诺。

## 9. 租约、重试与状态数据的设定

### 9.1 为什么租约用数据库时间

领取时计算 `lease_until = UTC_TIMESTAMP(3) + leaseMs`，回执时仍由数据库比较有效期。
这样 A、B 两个应用实例不会各用自己的本地时钟判定同一份租约，token 又保证每次接管拥有不同身份。
这不阻止租约到期的旧实例继续把消息发到 Broker；它只能防止旧实例再覆盖数据库状态，重复消息仍需消费者幂等。

30 秒租约大于默认 3 秒单次发送，属于当前开发环境的预算选择，没有经过生产负载下的最优值验证。
每次只领一条可以减少等待队列消耗租约，但网络抖动、线程暂停或数据库变慢仍可能让租约过期。

### 9.2 attempts 与 next_retry_at

attempts 在领取时增加，所以“领到后就退出”也计为一次尝试，不等于 Broker 一定收到了这么多次消息。
指数退避从 1 秒开始，最大 5 分钟；attempts 到达 Integer.MAX_VALUE 后封顶，任务不会因为次数大就自动删除。
next_retry_at 用于 PENDING；SENDING 是否能重新领取则看 lease_until，两种等待不能混淆。
当前非法消息也会保留重试，尚未实现持久化隔离及完整告警流程。

### 9.3 为什么业务与发送状态分开

请求 ACCEPTED、Outbox SENT、Redis RESERVED 可以同时成立。这表示已受理并已发送，正式建单尚未确认。
Outbox 发送器不因发送成功把请求改成 SUCCEEDED，也不因发送失败把它改成 CLOSED。
这样不会让生产者对消费者的最终业务结果越权作判断。

## 10. 字段类型、字符集与标识的设定

| 设定 | 当前考虑 | 当前限制与边界 |
|---|---|---|
| order/user/voucher 使用 BIGINT + Java Long | 与现有订单编号和实体类型一致，避免 32 位范围不足 | 正数校验主要在 Java；schema 没有为每个编号添加正数 CHECK |
| requestId/eventId 最多 128 字符 | 支持可读、稳定的业务标识 | 当前只允许 ASCII 字母、数字、下划线、中划线；不是任意用户输入 |
| payload_hash 使用 CHAR(64) | SHA-256 十六进制固定长度，当前摘要固定用户、券和数量 | 摘要不代替鉴权，也不覆盖 traceId 等非业务参数 |
| utf8mb4_bin | 区分标识大小写，避免 req-A 与 req-a 被当作同一标识 | 不将其泛化为任意字符串的逐字节相等；尾部空格等规则还与字段/排序规则有关，当前 ID 校验排除了空格 |
| status 使用 VARCHAR(16) + CHECK | 状态可读，数据库限制可存值 | CHECK 限制值集合，不证明状态转移顺序或跨表业务一致性 |
| event_type + schema_version | 区分逻辑事件与消息结构版本 | 当前只实现 CREATE_ORDER、版本 1；不是已完成多事件通用平台 |
| payload 使用 MEDIUMTEXT | 保存 JSON 业务字段，重试不重新生成事件身份 | 数据库容纳能力不代表 MQ 支持同样大的消息；当前未新增统一消息体大小校验 |
| last_error 只存异常类型 | 提供基本诊断，不把完整敏感上下文塞入表 | 完整诊断仍依赖日志，不代表已完成告警体系 |

Redis 脚本把大编号和 epoch 当字符串比较，不把完整 Long 编号转换成 Lua number，避免浮点精度丢失。
JavaScript 前端也存在大整数精度边界，后续结果查询应明确字符串编号契约；当前还没有统一改造所有 HTTP Long 序列化，不能写成已完成。

### 10.1 为什么没有物理外键

当前以稳定编号进行逻辑关联，迁移 SQL 没有给三表建立外键。这样方便阶段扩展和保留失败请求，但也使数据库不能单靠 FK 保证所有引用存在。
即使增加外键，也不能表达“ACCEPTED 必须有对应 CREATE_ORDER 且 Redis 预占有效”等完整业务规则；仍需正确事务与恢复程序。
当前选择不是所有系统都应照搬的固定规范，未来若增加维护入口，要重新评估引用完整性和清理顺序。

### 10.2 索引为什么这样排

主键处理单条请求/事件定位，唯一索引处理去重；状态、时间、编号组合为恢复及到期领取提供查询条件。
由于 Outbox 查询包含 PENDING 到期与 SENDING 租约到期的 OR 分支，索引存在不代表已证明任何数据规模下都是最优执行计划。
当前没有把 EXPLAIN、大规模积压扫描和长时间锁竞争压测计入已验证成果。

## 11. UTC 与 DATETIME(3) 的考虑

DATETIME(3) 表示保存到毫秒，但字段本身不携带时区，也不会因为列类型自动把任意输入转换成 UTC。
本项目约定这些新表时间按 UTC 表达：默认时间、更新、租约与退避 SQL 使用 UTC_TIMESTAMP(3)。
acceptedAt 则使用 Java Instant.now() 并截断到毫秒，减少消息字段与数据库精度不一致。
[MySQL 日期时间类型说明](https://dev.mysql.com/doc/refman/8.4/en/datetime.html)

连接串的 `serverTimezone=UTC` 指定驱动解释/转换时间时的时区配置，不能因此断言 MySQL 服务器或每个客户端会话的 time_zone 已被修改。
驱动还有独立的会话时区设置行为，本次 CLI 读取 SESSION time_zone=SYSTEM 与这个配置并不矛盾。
[Connector/J 时间属性说明](https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-datetime-types-processing.html)

| 时间字段/值 | 时间来源 | 为什么这样用 |
|---|---|---|
| created_at、updated_at、next_retry_at、lease_until | 数据库 UTC 时间 | 任务调度、租约判断尽量在一个时钟体系完成 |
| accepted_at、消息 acceptedAt | 应用 Instant | 表达本次受理时间，截断为毫秒；是成功受理事务确定的字段 |
| Lua 活动时间判断 | Redis TIME | 脚本执行时在 Redis 侧判断活动窗口 |

不同系统的时钟没有被这段代码自动校准。不能简单把应用 acceptedAt 与另一个机器的日志时间相减，就认为得到无误差的端到端延迟。
租约判断只使用数据库时间，是缩小影响范围，而不是宣称全链路时间完全一致。

## 12. 数据持久化与“提交成功”的边界

InnoDB 提交、Broker 持久化、Redis 预占可恢复性是不同层的保证。
当前读取到的 MySQL redo/binlog 配置为 1/1，是本地提交持久化设置的一部分，不说明已经具备副本切换、备份恢复或多份存储同时损坏后的零丢失能力。

应用拿到提交异常时，既可能没提交，也可能已经提交但响应丢失；所以必须沿用原 requestId 查询，不能立即更换订单号或补库存。
同理，MQ 收到成功回执和消费者建单完成之间还有独立的业务事务及 ACK 边界，尚待第三阶段实现。

当前没有自动清理请求/Outbox 或为 Redis 预占设置短 TTL，是保留恢复依据的选择，不是完整的数据生命周期管理。
未来应定义按终态、消息重放窗口、补偿窗口的归档规则；不能只按创建时间删除仍在处理的记录。

## 13. 如何检查隔离级别，避免看错连接

在数据库客户端可以执行只读查询：

```sql
SELECT VERSION(), CONNECTION_ID(),
       @@GLOBAL.transaction_isolation AS global_isolation,
       @@SESSION.transaction_isolation AS session_isolation,
       @@SESSION.autocommit AS autocommit;

SELECT @@GLOBAL.innodb_flush_log_at_trx_commit,
       @@GLOBAL.sync_binlog, @@GLOBAL.binlog_format,
       @@GLOBAL.innodb_lock_wait_timeout,
       @@SESSION.time_zone, UTC_TIMESTAMP(3), NOW(3);
```

DataGrip/IDEA 数据库控制台的连接与 Java 事务连接不同；控制台显示 RR 不足以否定新模板显式设置 RC。
若要验证实际事务，查询必须在该模板回调所使用的同一事务连接上执行，或在调试中查看事务绑定 Connection 的隔离级别。
不要为了验证临时通过 SET GLOBAL 改掉整个开发库配置；本次仅做了只读的服务器参数核对，没有执行这样的修改。

## 14. 当前已验证与仍需验证

已有隔离集成测试验证了重复请求归并、20 用户争抢 5 份库存、Outbox 写失败回滚、提交响应丢失、SKIP LOCKED 和租约令牌失效等场景。
它们支持当前协议的指定断言，不等同于新写了 RC/RR 对照测试、连接池耗尽测试、死锁恢复测试或真实多实例集群压测。
本次只读检查确认服务器默认参数，Spring/Mapper 设置依据当前源码及缓存依赖实现核对，没有运行新的业务写入测试。

后续复盘优先回答：

1. 哪个约束保护哪种唯一性？是否错误地把 RC、Java 先查或 Redis 锁当作数据库业务唯一约束？
2. 所有 ACCEPTED 写入口是否都同时登记待发事件？是否有人绕过接口手工改状态？
3. 新调用层有没有包外层大事务，使 REQUIRES_NEW 额外占连接或改变前置读取语义？
4. 是否把 5 秒事务预算、3 秒网络等待和 30 秒租约理解成同一个超时？
5. 若保留当前三表，租约与控制行支持范围是否有真实验证？若简化成单表，哪些不变量仍必须成立？
6. 正式消费者、业务唯一索引、周期恢复与补偿尚未完成时，是否过早宣称订单迁移已完整上线？

## 15. 代码与资料索引

- [SeckillRequestServiceImpl](../../../backend/src/main/java/com/hmdp/service/impl/SeckillRequestServiceImpl.java)：accept、acceptLocked、transaction，两个受理事务及异常边界。
- [OrderOutboxDispatcher](../../../backend/src/main/java/com/hmdp/infrastructure/mq/OrderOutboxDispatcher.java)：领取、事务外发送、回执与重试事务。
- [SeckillRequestMapper](../../../backend/src/main/java/com/hmdp/mapper/SeckillRequestMapper.java)、[控制行 Mapper](../../../backend/src/main/java/com/hmdp/mapper/SeckillCampaignControlMapper.java)、[MqOutboxMapper](../../../backend/src/main/java/com/hmdp/mapper/MqOutboxMapper.java)：锁定读、唯一键归并、条件更新。
- [迁移 SQL](../../../backend/src/main/resources/db/migration/V20261024_02__seckill_request_outbox.sql)：字段、CHECK、唯一约束与索引。
- [application.yaml](../../../backend/src/main/resources/application.yaml)、[受理配置](../../../backend/src/main/java/com/hmdp/config/SeckillOrderProperties.java)、[Outbox 配置](../../../backend/src/main/java/com/hmdp/config/OrderOutboxProperties.java)：连接、预算与扫描参数。
- [Redis 预占脚本](../../../backend/src/main/resources/lua/seckill/reserve-order.lua)、[消息结构](../../../backend/src/main/java/com/hmdp/infrastructure/mq/SeckillOrderMessage.java)：标识、时间精度及状态含义。
- Spring Framework 6.2.15 本地缓存源码：TransactionTemplate、DataSourceTransactionManager、DataSourceUtils，核对回滚、连接与超时语义。
- MyBatis 3.5.16 / MyBatis-Spring 3.0.4 本地缓存源码：BaseStatementHandler、StatementUtil、SpringManagedTransaction，核对语句预算与事务剩余时间。
