# 订单 RocketMQ 第四阶段：自动发布、预占补偿、请求恢复与运维

> **第五阶段衔接**：第四阶段及注释、命名规则已提交为 `58ba8cdd`。按用户最新要求，后续只走新路径，旧入口和旧消费者停止注册，旧 Stream 排空及历史迁移不再实施。新路径启用、恢复并发修复和本轮验证见 [第五阶段说明](ROCKETMQ_PHASE5.md)。下文保留第四阶段交付记录。

配套图解：[第四阶段 16 个独立 Mermaid 流程](ROCKETMQ_PHASE4_FLOWS.md)，逐图说明事务、锁、成功及异常分支。
脚本内部图解：[第四阶段新增 Lua Mermaid 流程](ROCKETMQ_PHASE4_LUA_FLOWS.md)，逐个核对初始化、确认释放及预占核对的分支。

日期：2026-10-25。本轮按用户追加要求完成第四阶段四项实现后统一验收。
[第 4.1 步记录](ROCKETMQ_PHASE4_1.md) 保留首次交付时的细节和验证结果；本文件为第四阶段当前完整边界。
第五阶段的故障压测、旧 Stream 排空、业务切换和回滚演练未开展。未提交或推送代码。

## 1. 解决的业务问题、原行为与案例

第三阶段能可靠受理和幂等建单，但发券还要手动初始化，确认/释放任务一直 PENDING，
PREPARING/ACCEPTED 只能依赖客户端重试，死信及积压缺少可操作的处理入口。

| 本步 | 修改前 | 完成后的行为 | 具体例子 |
|---|---|---|---|
| 4.1 自动发布 | 创建券只写旧库存键 | 新券先落库为 PAUSED/PENDING，准备新 stock/meta 后开放 READY/PUBLISHED | 库存 10 的新券正常创建后可直接走新受理接口 |
| 4.2 确认和释放 | SUCCEEDED/CLOSED 只有任务记录 | 租约执行器核对订单事实，以 Lua 幂等推进预占；任务失败退避 | 成功订单 Redis 9 保持 9；关闭未建单请求 Redis 9 回补到 10，重复释放仍为 10 |
| 4.3 异常恢复 | PREPARING、ACCEPTED 长期悬挂 | 后台定量核对并沿用原编号恢复受理或重投；冲突暂停并审计 | Lua 扣库后 Outbox 写失败，后台继续原订单受理，不再生成编号或扣第二份 |
| 4.4 排查和运维 | MQ 超限后缺少持久处置、监控 | 非法消息及死信可靠隔离，提供健康指标、日志告警和双重授权的重投/关闭/处置 | 死信落表后仍保留 ACCEPTED，不因死信自动回补；运维核对后重投原事件 |

需要防止的并发错误：

1. A 释放了库存但响应丢失；B 接管过期任务重试。若重复 INCR 会凭空增库存，故 Lua 只在 RESERVED 首次转 RELEASED 时回补。
2. A 释放订单 1001 后，同用户用新请求预占订单 1002；1001 的旧释放再次到达。不能删除归属于 1002 的用户记录。
3. A 显式关闭请求，B 消费者同时建单。两者都先锁活动再锁请求，只有一方能推进终态；后到者核对已有状态，不能同时成功和回补。
4. PREPARING 的 Redis 命令超时后延迟到达。关闭请求必须登记持久释放任务；没有预占时写 RELEASED 墓碑，迟到命令看到终态不能扣库存。
5. 旧代次释放任务迟到。它不能改新代次库存，也不能因旧请求冲突把新活动暂停。

关闭入口处理的是“尚未建单的抢购请求”，不是已创建订单的取消、支付超时关单或退款。
发送超时、请求年龄和进入死信均不是业务取消依据。

## 2. 文件职责和改动说明

路径相对于项目根目录。第 4.1 步具体文件与前后对比见其独立文档，仍包含在本轮最终差异中。

| 文件 | 原有职责 / 本轮变化 | 业务原因 |
|---|---|---|
| `backend/src/main/java/com/hmdp/service/IVoucherService.java`、`service/impl/VoucherServiceImpl.java` | 第 4.1 步新增两段事务发布及原券重试 | 沿用发券 Service，不为初始化另建转调服务 |
| `backend/src/main/java/com/hmdp/controller/VoucherController.java` | 第 4.1 步返回待确认原编号及初始化入口 | 发布异常后不盲目重新发券 |
| `backend/src/main/java/com/hmdp/mapper/SeckillCampaignControlMapper.java`、`entity/SeckillCampaignControl.java` | 第 4.1 步控制行插入、排他锁、READY 更新；补充 BLOCKED 状态说明 | 发布与现有共享锁协议协调；冲突不能被首次发布扫描重新开放 |
| `backend/src/main/java/com/hmdp/service/ISeckillMaintenanceService.java` | 新增维护协议 | 将批处理、重投、关闭、隔离处置和只读监控的约定集中说明 |
| `backend/src/main/java/com/hmdp/service/impl/SeckillMaintenanceServiceImpl.java` | 新增维护业务实现 | 独立组织任务租约、数据库事实核对、Redis 执行、恢复、关闭和审计事务，不依赖用户请求线程 |
| `backend/src/main/java/com/hmdp/mapper/SeckillMaintenanceMapper.java` | 新增维护持久层 | 有界扫描、恢复退避、任务锁、重投条件更新、审计、隔离及健康查询；不自行推导业务状态 |
| `backend/src/main/java/com/hmdp/infrastructure/seckill/SeckillReservationStore.java` | 原有预占基础上增加初始化、确认/释放与只读预占核对 | Redis 操作继续收敛在基础设施，沿用同一套键构造 |
| `backend/src/main/resources/lua/seckill/initialize-campaign.lua` | 第 4.1 步新增初始化协议 | 只首次创建或只读核对，不重置已有库存 |
| `backend/src/main/resources/lua/seckill/finish-reservation.lua` | 新增确认/释放协议 | 身份、代次、数量、用户归属一起校验，状态幂等且不设置短 TTL |
| `backend/src/main/resources/lua/seckill/verify-reservation.lua` | 新增恢复前只读原子核对 | 重投前不能只查一个库存数字就认定预占有效 |
| `backend/src/main/java/com/hmdp/service/impl/SeckillRequestServiceImpl.java` | 只将已知 Redis 状态冲突改为专用异常，受理事务顺序不变 | 恢复程序能区分数据冲突与网络结果未知；外层仍包装原待确认异常 |
| `backend/src/main/java/com/hmdp/exception/SeckillConsistencyException.java` | 新增一致性异常 | 已知事实冲突触发暂停与审计，不把所有网络超时都当数据损坏 |
| `backend/src/main/java/com/hmdp/exception/SeckillCampaignPublicationPendingException.java` | 第 4.1 步新增，携带已提交券编号 | 表达发布未确认，不谎称确定发布失败 |
| `backend/src/main/java/com/hmdp/constants/SeckillOrderFailureReason.java` | 增加两种运维关闭原因 | 区分原 PREPARING 与 ACCEPTED，只有前者允许缺预占时写零数量墓碑 |
| `backend/src/main/java/com/hmdp/config/SeckillMaintenanceProperties.java` | 新增配置组件 | 限制批次、扫描间隔、恢复退避、租约和告警阈值，运维接口必须配置强令牌 |
| `backend/src/main/java/com/hmdp/config/SeckillMaintenanceSchedulingConfiguration.java`、`infrastructure/seckill/SeckillMaintenanceJob.java` | 新增条件调度 | 维护调度不依赖 MQ 投递开关；单轮故障记录后下一轮仍可运行 |
| `backend/src/main/java/com/hmdp/infrastructure/mq/OrderMessageFailureStore.java`、`entity/QuarantinedOrderMessage.java` | 新增有界原文及摘要持久化 | 同一来源、Topic、物理消息编号及正文摘要重复接收不重复存储，隔离提交后才确认 |
| `backend/src/main/java/com/hmdp/infrastructure/mq/RocketMqOrderListener.java` | 原正式消费适配；新增非法协议消息可靠隔离，业务失败继续重试 | 区分协议毒消息和账本/数据库故障，不把业务异常误确认掉 |
| `backend/src/main/java/com/hmdp/infrastructure/mq/RocketMqDeadLetterListener.java` | 新增死信隔离监听 | 不根据 DLQ 自动关单；隔离失败交 Broker 重试 |
| `backend/src/main/java/com/hmdp/config/OrderConsumerProperties.java`、`config/RocketMqConfiguration.java` | 增加独立死信开关、组及客户端生命周期 | 订阅当前订单组的 `%DLQ%<group>`，使用另一消费组，沿用既有 ACL |
| `backend/src/main/java/com/hmdp/controller/SeckillMaintenanceController.java` | 新增默认关闭的运维接口 | 登录态和独立运维令牌同时满足后才允许操作；操作者取可信上下文 |
| `backend/src/main/resources/application.yaml` | 增加维护、DLQ 和运维参数 | 开发维护及死信观察默认开启，运维写接口默认关闭；保留所有既有业务开关 |
| `backend/src/main/resources/db/migration/V20261025_04__seckill_campaign_publication.sql` | 第 4.1 步发布列迁移 | 历史控制行默认 UNMANAGED，不自动接管 |
| `backend/src/main/resources/db/migration/V20261025_05__seckill_maintenance.sql` | 新增三张维护表 | 持久保存恢复尝试、运维审计和消息隔离，不改变原 Outbox 状态枚举 |
| `backend/src/test/java/com/hmdp/infrastructure/mq/SeckillOutboxIT.java` | 扩展原真实依赖测试 | 验证补偿、恢复、关闭事务、竞争、过期租约及真实 Broker 死信隔离；保留前阶段测试 |
| `backend/src/test/java/com/hmdp/config/OrderPipelineConfigurationTest.java`、`config/RocketMqConfigurationTest.java` | 扩展上下文及客户端测试 | 验证开关组合、关闭路径、死信订阅与启停 |
| `backend/src/test/java/com/hmdp/controller/SeckillMaintenanceControllerTest.java` | 新增 MVC 契约测试 | 覆盖无登录/无令牌/错令牌、可信操作者、参数绑定和健康查询 |
| `backend/src/test/java/com/hmdp/infrastructure/mq/RocketMqOrderListenerTest.java`、`mq/RocketMqDeadLetterListenerTest.java` | 修改原非法消息预期并新增 DLQ 用例 | 隔离提交后确认，隔离不可用则重试 |
| `backend/src/test/java/com/hmdp/infrastructure/seckill/SeckillMaintenanceJobTest.java` | 新增调度与日志测试 | 验证积压告警及单轮失败后后续仍可调用 |
| `backend/src/test/resources/db/seckill-order-tables.sql`、第 4.1 步两份测试 | 第 4.1 步沿用 | 支持真实自增发券及发券分支验证，不算后续步骤新增 |
| 本文、文档索引、项目入口、购买指南、第三阶段及设计方案衔接说明 | 仅文档更新 | 区分阶段历史、当前实现、联调证据和第五阶段边界 |

新维护 Service 有独立事务、事实核对、租约与运维职责，不是单方法转调包装；与在线受理、建单和旧 Stream 生命周期分开。
既有建单代码及事务不移动：库存、订单、成功/关闭账本和确认/释放任务同事务提交仍是第三阶段成果。

## 3. 关键代码前后对比与解释

### 3.1 从只登记任务到执行任务（新增执行器）

修改前：第三阶段只调用 `insertReservationTask` 保存 PENDING，没有处理者。
本轮新增的主要语句是：

```java
// 短事务 A：领取本地任务并提交租约。
MqOutbox task = maintenance.lockNextReservationTask();
outbox.claim(task.getEventId(), token, properties.getLeaseMs());

// 短事务 B：活动共享锁 -> 请求排他锁 -> 任务排他锁 -> 核对租约。
reservations.finish(voucherId, userId, orderId, epoch, confirm, allowMissing);
outbox.markSent(eventId, token, confirm ? "redis-confirmed" : "redis-released");
```

领取 SQL 只选 `CONFIRM_RESERVATION/RELEASE_RESERVATION`，并使用 `SKIP LOCKED` 跳过其他领取者。
事务 A 已结束才进入执行，避免长期占住领取查询的锁。事务 B 中先核对账本、原订单及代次，再调用 Redis。
确认要求 SUCCEEDED 和归属匹配的原订单；释放要求 CLOSED 且原订单不存在。
任务 payload 还要与账本的用户、券、事件前缀、跟踪号及受理时间完整一致。

任务复用 Outbox 的 PENDING/SENDING/SENT 和带令牌回执协议；本地任务 SENT 表示 Redis 执行已确认，
不是发到 Broker。MQ 投递器仍只领 CREATE_ORDER；没有改变其消息路由。
失败退避最长五分钟，任务不因超过次数而删除；达到阈值告警。进程退出后租约到期可接管。

### 3.2 确认和释放 Lua（新增）

```lua
-- 确认先校验 RESERVED/CONFIRMED、quantity=1 和当前用户归属。
redis.call('HSET', reservationKey, 'state', 'CONFIRMED')

-- 仅 RESERVED 首次释放执行以下两句。
redis.call('INCR', stockKey)
redis.call('HDEL', usersKey, userId)
-- 最后记录 RELEASED；再次执行只核对原记录，不再 INCR/HDEL。
```

上述语句前都已检查键类型、库存整数范围、meta.ready、meta.epoch、订单/用户/券/数量和归属。
确认不减少 Redis 库存；CONFIRMED 拒绝释放。已 RELEASED 的旧任务不能删除后来属于新订单的用户记录。

只有明确关闭原 PREPARING 的请求允许“预占不存在时写 RELEASED、quantity=0 墓碑”。
对于原 ACCEPTED 的请求，缺预占是数据冲突，不能猜测扣了多少库存然后把任务标为完成。
REJECTED 零数量记录可转释放墓碑，不删除其他订单归属。
墓碑、确认及用户归属都不设置短 TTL；迟到预占看到 RELEASED 后不再扣减。

### 3.3 从客户端恢复到后台恢复（新增）

```java
// PREPARING：从账本读取原 userId/voucherId/requestId/traceId。
acceptance.accept(userId, voucherId, requestId, traceId);

// ACCEPTED：锁内核对订单、Redis 预占和原 Outbox，再重投原事件。
String reservation = reservations.verify(voucherId, userId, orderId, epoch);
MqOutbox event = maintenance.lockEvent("order-" + orderId);
// 缺事件才从原账本补建；存在时必须与账本构造出的原消息完全相同。
maintenance.replayEvent(event.getEventId());
```

PREPARING 先检查是否已经存在原订单；有原订单却仍是 PREPARING 时暂停核对，不再次预占。
继续调用已有受理服务会重新锁活动及请求，保持原事务顺序，并复用已存在的请求记录，因此不生成新订单号。
原预占已 RESERVED 时，既有 Lua 返回原结果，不再扣一次。
明确拒绝则沿用原账本转 REJECTED；网络超时继续保留 PREPARING。

ACCEPTED 核对活动 READY 与代次、原订单归属以及有效 RESERVED/CONFIRMED 预占。
CONFIRMED 却没有数据库订单是冲突。原订单存在且归属一致时也重投原消息，让既有消费者收敛成功账本，不再次扣数据库库存。
原 Outbox 缺失时重建相同 eventId/orderId/traceId/acceptedAt；存在但 payload 不一致时不替换，暂停并审计。
自动恢复只重投 SENT；PENDING/SENDING 继续由原投递器处理，不抢正常退避或有效发送租约。
人工重投可以提前唤醒 PENDING 或过期租约，但也不能抢尚未过期的发送租约。

恢复尝试表先原子领取并写下一次时间，再进行网络/业务调用；失败也留下次数和异常类型。
扫描按下一次时间有界选取，坏请求会退出本轮到期集合，避免占满每一批。
PAUSED/PENDING 新券也由恢复任务重试首次发布；遇到已知初始化数据冲突则标记 BLOCKED，不再自动开放。

### 3.4 显式关闭与消费者竞争（新增）

```java
// 活动共享锁、请求排他锁内：只能处理 PREPARING/ACCEPTED，且原订单不存在。
maintenance.closeRequest(request);
outbox.insertEvent(event(request, RELEASE_RESERVATION_EVENT_TYPE));
maintenance.audit(orderId, voucherId, "CLOSE", operator, reason);
```

关闭、释放任务和审计必须同事务成功。任务写失败则关闭一起回滚，Redis 尚未被修改。
原 PREPARING 固定当前代次及任务时间，并记录 `OPERATOR_CLOSED_PREPARING`，供释放脚本判断是否允许零数量墓碑。
原 ACCEPTED 保持原受理时间和代次，原因是 `OPERATOR_CLOSED`。
后到的 CREATE_ORDER 消费看到 CLOSED 就结束；消费者先成功时，关闭入口拒绝，不回补成功订单。
重复关闭已终态请求返回失败，不新增任务或库存操作。

### 3.5 从非法消息重试到提交隔离后确认（修改）

修改前：解析失败返回 RECONSUME_LATER，最终由 Broker 决定进入死信。
修改后：

```java
try {
    order = objectMapper.readValue(message.getBody(), SeckillOrderMessage.class);
} catch (IOException | IllegalArgumentException ex) {
    failures.record(message, "INVALID", ex.getClass().getSimpleName());
    continue; // record 返回前已经完成独立数据库提交。
}
orders.createFromMessage(order); // 业务失败仍重试，不当作解析错误确认。
```

非法标签、缺正文、超过 64 KiB、JSON/版本错误均需可靠隔离才能确认；隔离写失败仍要求 Broker 重试。
合法消息的业务身份冲突、锁冲突、数据库异常继续 Broker 重试，不能因为捕获 IllegalArgumentException 就错误确认。
死信观察消费者订阅 `%DLQ%<当前建单组>`，使用独立消费组；它只隔离，绝不直接关闭请求或调用释放脚本。
隔离表保存消息摘要和最多 64 KiB 原文，超大消息仅保存摘要；原文不输出到日志或 HTTP 摘要接口。
重复隔离不删除或覆盖已有人工处置。运维重投可关联隔离编号，原文须解析为与账本完全相同的原消息，重投和处置审计一起提交。

## 4. 完整业务流程、锁与失败分支

### 正常完成

1. 发券事务提交两张券表及 PAUSED/PENDING 控制行。
2. 发布事务取活动排他锁，Redis 初始化成功，提交 READY/PUBLISHED 后释放数据库锁。
3. 受理事务先活动共享锁、再请求排他锁，Redis 预占后提交 ACCEPTED + CREATE_ORDER Outbox。
4. Outbox 短事务领取、事务外发送 MQ、短事务用租约令牌确认。
5. 消费者先活动共享锁、再请求排他锁，数据库扣库、插单、SUCCEEDED 和确认任务一起提交，再确认消费。
6. 本地执行器领取确认任务；活动共享锁、请求锁、任务锁内核对真实订单，Redis RESERVED -> CONFIRMED，提交任务 SENT。

关闭分支：步骤 5 确定建单失败，或受控关闭先获得请求锁时，同事务提交 CLOSED + RELEASE_RESERVATION。
本地释放执行器核对 CLOSED 且原订单不存在，只回补该订单自己的预占；原 PREPARING 无预占时写墓碑。
暂停会阻止新受理、首次建单和重投；同代次且事实一致的已登记确认/释放仍可执行。
重建若需要静止快照，必须持有活动排他锁协调全部新链路写入，不能只改 PAUSED 就假定后台也不再处理。

本阶段不涉及商铺布隆同步、空值缓存清理或缓存互斥锁。活动维护锁为 MySQL 控制行锁，
Lua 用同券 hash tag 的新命名空间；没有增加业务类自行拼接的 Redis 锁。

| 分支 | 行为 |
|---|---|
| 锁竞争 | 活动、请求及指定事件使用 NOWAIT，立即失败；领取任务使用 SKIP LOCKED 跳过竞争行；没有绕过锁继续的分支 |
| SQL/事务超时 | 当前事务回滚，持久任务或恢复尝试保留；不会因超时自动关闭 |
| Redis 命令结果未知 | 任务保留退避；再次执行 Lua 核对原状态，不能认定未执行并盲目补偿 |
| Redis 成功后回执事务回滚 | Redis 状态保留，Outbox 待办可接管重试；Lua 幂等避免再次回补 |
| 租约过期 | 新领取者换令牌；旧令牌回执更新 0 行，不覆盖新领取者；旧 Lua 已执行也由状态幂等保护 |
| 已关闭或已成功的迟到消息 | 沿用第三阶段终态语义，不重新建单或释放成功订单 |
| 中断 | 入口已中断时不开始新工作；Redis 前后再次检查，保留中断标记，当前任务待重试；不当成业务取消 |
| 键缺失/身份/代次冲突 | 保留任务和原业务状态，另起事务取活动排他锁；同代次暂停为 PAUSED/BLOCKED 并写冲突依据 |
| 旧请求代次与当前活动不同 | 不写 Redis、不暂停新代次，保留原任务与审计供人工核对 |
| DLQ/非法消息隔离库不可用 | 不确认当前消息，Broker 重试；观察消费者设置不自动耗尽自身的隔离重试 |
| 配置/组件未启用 | 可靠组件关闭时维护 Service、任务和运维入口均不注册；运维默认关闭，关闭后不强制要求令牌 |
| 告警或扫描失败 | 记录结构化失败事件，下一轮调度仍执行；数据库不可用时不能承诺当场完成暂停或审计 |

暂停发生在原处理事务结束后，避免把活动共享锁升级为排他锁造成死锁。
若恢复/暂停事务本身碰到数据库故障或锁冲突，任务和退避记录仍在，日志保留失败；后续重试继续核对。
READY/PUBLISHED 后的维护暂停不能通过首次初始化入口重新开放。

## 5. 方案选择与可观测性

- **复用 Outbox 租约**：确认/释放与建单本来同事务登记，单独领取本地类型即可，无须再发一遍 MQ 或引入另一张补偿队列表。
- **数据库终态决定操作资格**：Redis 确认只处理成功订单，释放只处理无原订单的关闭请求，避免死信、超时导致错误退款式回补。
- **原编号恢复**：扫描和人工重投不接收替换 payload，缺事件只从账本重建，保留事件时间与身份。
- **持久化退避**：应用退出不丢恢复计划；多实例同一资源只有一个当前领取资格，即使后续再次执行也依赖业务锁和 Lua 幂等。
- **冲突暂停与首次发布分开**：BLOCKED 不能被 PENDING 扫描自动重开，人工先修事实，不能通过清键、SET 库存掩盖异常。
- **默认关闭运维入口**：额外开启后仍需登录和至少 32 字符独立令牌；操作者不从请求正文读取；写操作必须有原因和审计。

健康接口 `GET /ops/seckill/health` 的数字包括：

| 指标 | 意义 |
|---|---|
| pending_tasks / pending_create_events / pending_confirmations / pending_releases | 持久任务总量与分类型数量 |
| expired_leases / max_attempts / oldest_task_seconds | 过期租约、最大尝试次数、最老任务年龄 |
| unfinished_requests / preparing_requests / accepted_requests / oldest_request_seconds | 未终态请求数量、分状态数量和最老请求年龄 |
| pending_campaigns / oldest_campaign_seconds / blocked_campaigns | 首次发布待办、年龄和冲突暂停活动 |
| quarantine_pending / recovery_errors | 未处置隔离消息、仍关联活跃请求或待发布活动的恢复失败 |

周期监控按年龄、尝试次数、冲突暂停、隔离待办及恢复错误发出 `SECKILL_BACKLOG_ALERT`。
其他可订阅事件：`SECKILL_CONSUME_RETRY`、`SECKILL_TASK_RETRY`、`SECKILL_CONSISTENCY`、
`SECKILL_MESSAGE_QUARANTINED`、`SECKILL_MAINTENANCE_FAILURE`、`SECKILL_MONITOR_FAILURE`。
这是应用日志告警及 HTTP 查询能力；未配置邮件、短信或第三方告警平台。
数据库指标反映业务待办，不等于 Broker 精确积压/消费位点；Broker 队列深度仍由现有管理端观察。

## 6. 迁移、配置与联合验收

### 6.1 迁移及启动条件

先完成第二、三阶段迁移。从项目根目录执行第四阶段两份脚本，密码由客户端交互输入：

```bash
mysql -h 127.0.0.1 -P 3306 -u root -p hm-dp \
  < backend/src/main/resources/db/migration/V20261025_04__seckill_campaign_publication.sql
mysql -h 127.0.0.1 -P 3306 -u root -p hm-dp \
  < backend/src/main/resources/db/migration/V20261025_05__seckill_maintenance.sql
```

迁移不由应用自动执行。本轮仅在随机隔离库反复执行，没有修改开发业务库。
表结构 DDL 会隐式提交；备份目标库后应用。回滚代码可以保留新增表和列，不删除业务数据或任务。

| 参数 | 当前开发 YAML 默认值 | 含义 |
|---|---|---|
| SECKILL_RELIABLE_COMPONENTS_ENABLED | true | 注册新受理、建单、预占及维护服务；不切换旧 HTTP |
| SECKILL_MAINTENANCE_ENABLED | true | 开启本地任务、后台恢复及监控 |
| SECKILL_MAINTENANCE_BATCH_SIZE | 20 | 每轮本地任务最多 20；恢复每类最多 20 |
| SECKILL_MAINTENANCE_INTERVAL_MS | 5000 | 本地任务及恢复扫描间隔 |
| SECKILL_RECOVERY_STALE_SECONDS / SECKILL_RECOVERY_RETRY_SECONDS | 60 / 60 | 请求多久无进展才核对、每资源重试间隔秒数 |
| SECKILL_MAINTENANCE_LEASE_MS | 30000 | 本地任务租约毫秒数 |
| SECKILL_ALERT_AGE_SECONDS / SECKILL_ALERT_ATTEMPTS | 120 / 10 | 年龄与尝试次数告警阈值 |
| ORDER_DEAD_LETTER_ENABLED / ORDER_DEAD_LETTER_GROUP | true / hmdp-seckill-order-dlq-cg | 当前建单组死信观察及独立消费组 |
| SECKILL_OPERATIONS_ENABLED / SECKILL_OPERATIONS_TOKEN | false / 空 | 默认不开放运维接口，开启时必须配置至少 32 字符令牌 |

Java 属性默认不开启维护/运维/DLQ；上表是开发 YAML 的显式默认值。
MQ 的总开关、消费者开关和可靠组件开关共同控制死信客户端，维护调度不依赖 Outbox 发送开关。
监控日志默认每 30 秒检查，可用 `hmdp.seckill.maintenance.monitor-interval-ms` 调整。

真实 DLQ 观察需要 Broker 对 `%DLQ%<实际 ORDER_CONSUMER_GROUP>` 开放读取权限，ACL 允许当前账号订阅该 Topic 和独立组。
有的死信 Topic 默认只可写，必须先在 RocketMQ 管理端核对。应用不自动修改正式 Broker 权限。
本轮真实用例仅给随机测试组创建可读 DLQ；这不等于正式消费组的运维权限已经配置。

### 6.2 统一业务验收

1. 按 [4.1 文档](ROCKETMQ_PHASE4_1.md) 从接口创建全新活动，核对 READY/PUBLISHED、epoch 和新 stock/meta。
2. 使用第三阶段新受理 API（或显式启用 `VITE_RELIABLE_SECKILL_ENABLED=true` 的前端）抢一张券。
3. 查询到 SUCCEEDED 后，核对确认任务最终 SENT、reservation.state=CONFIRMED、Redis/数据库库存均少一。
4. 在隔离环境创建未建单的 ACCEPTED 请求，暂停测试消费者后受控关闭；恢复任务后核对 CLOSED、释放 SENT、Redis 回补一份。
5. 重复执行同一释放任务、再发迟到 CREATE_ORDER，库存不增加第二份、不生成被关闭订单。
6. 通过隔离测试模拟 Lua 后受理事务失败，后台使用原 requestId/orderId 恢复，检查只扣一份。
7. 通过隔离测试验证 SENT 长期 ACCEPTED 重投原 payload，已有原订单时不重复扣数据库。
8. 读取健康及隔离摘要；检查日志告警，按审计原因对有效隔离消息重投，确认原业务编号保持一致。

不要用旧 Stream 入口购买自动发布到新命名空间的活动。新旧代码并存不代表同券混流安全。
本阶段仍保留原前端默认开关与旧消费者，不排空或停用旧 Stream。

### 6.3 运维接口

启动前自行设置独立运维令牌及开启开关。以下命令在项目根目录的终端执行，令牌和登录凭据从环境读取：

```bash
# HMDP_ACCESS_TOKEN 为当前有效登录凭据，SECKILL_OPERATIONS_TOKEN 为独立运维令牌。
curl -sS http://localhost:8081/ops/seckill/health \
  -H "authorization: Bearer $HMDP_ACCESS_TOKEN" \
  -H "X-Seckill-Ops-Token: $SECKILL_OPERATIONS_TOKEN"

curl -sS http://localhost:8081/ops/seckill/quarantine \
  -H "authorization: Bearer $HMDP_ACCESS_TOKEN" \
  -H "X-Seckill-Ops-Token: $SECKILL_OPERATIONS_TOKEN"
```

写操作均为 POST，正文只包含原因和可选隔离编号，不能提供用户编号或任意消息：

| 路径 | 示例正文 | 限制 |
|---|---|---|
| `/ops/seckill/orders/<原订单号>/replay` | `{"reason":"核对账本及 Broker 后重投","quarantineId":"可选的隔离编号"}` | 仅 ACCEPTED，身份/代次/预占/原事件一致；有效租约不能抢占 |
| `/ops/seckill/orders/<原订单号>/close` | `{"reason":"核对后明确取消未建单请求"}` | 仅 PREPARING/ACCEPTED、活动 READY、无原订单；实际释放异步执行 |
| `/ops/seckill/quarantine/<隔离编号>/dismiss` | `{"reason":"核对无效协议消息，无需建单"}` | 保留原文和审计，只标记该隔离待办已处置，不改变业务终态 |

运维接口关闭时没有注册路由。登录或令牌不满足时返回 403（全应用登录拦截可能提前返回 401）。
业务不符合条件沿用现有全局异常处理返回失败；不能把 HTTP 200 本身视为操作成功，应检查 Result.success。
需要检查隔离原文时由具备数据库权限的运维在隔离表核对，不通过 HTTP 返回原文。

### 6.4 自动化验证与证据边界

项目根目录执行：

```bash
# 真实 MySQL/Redis，Broker 用例按环境开关跳过。
SECKILL_OUTBOX_IT=true mvn -o -f backend/pom.xml '-Dtest=*Test,SeckillOutboxIT' test

# 同时验证现有 VM Broker 的投递、幂等建单和真实死信隔离。
SECKILL_OUTBOX_IT=true ROCKETMQ_SMOKE_TEST=true \
  mvn -o -f backend/pom.xml '-Dtest=*Test,SeckillOutboxIT' test
```

2026-10-25 最终回归：**239 项全部通过，0 失败、0 错误、0 跳过**。
其中 172 项单元、MVC、上下文和模拟客户端测试；67 项集成测试中，64 项使用真实 MySQL/Redis，3 项还使用真实 VM Broker。
真实 Broker 覆盖原投递验证、正式监听器幂等建单及本轮新增的转 DLQ 后可靠隔离。
与第三阶段 181 项相比，第四阶段累计增加 58 项；修改/扩展既有测试另行保留，不重复计数。
随后仅调整并发用例的锁竞争等待方式并单独复核两项并发用例，两项均通过；日志位于 `/private/tmp/hm-dp-phase4-concurrency.log`。
Surefire 报告会被后续单独运行覆盖，完整 239 项的统计以本轮保存的完整回归日志为准。
本轮最终命令及完整输出保存于本机 `/private/tmp/hm-dp-phase4-final-tests.log`，构建产物报告位于 `backend/target/surefire-reports/`。

测试使用随机数据库和随机券键，完成后精确清理；不加载完整应用、不启动旧 Stream 消费线程。
真实 MQ 使用 `hmdp-order-smoke` 前缀 Topic 及随机测试组；Broker 持久化的测试 Topic/消费组和测试消息保留，不擅自清理集群数据。
真实死信用 SDK 的 `delayLevelWhenNextConsume=-1` 让单条隔离测试消息直接进入该组 DLQ，
正式监听器正常保存隔离记录；生产消费者不设置此值，仍沿用配置次数的 Broker 重试。
此用例验证真实转死信与接收链路，不声称已经测试完整指数退避耗尽的长时间过程。

模拟/上下文测试负责开关、授权、告警、客户端启停、异常分支和方法调用顺序。
真实 MySQL/Redis 测试负责事务回滚、唯一约束、锁竞争、Lua 状态及库存、恢复退避和隔离审计。
Redis 执行后响应丢失、数据库回执提交前失败由真实操作之后的异常注入模拟；不是实际拔网线或杀进程压测。
HTTP 运维契约使用 MockMvc，不冒充真实登录服务器和浏览器全流程；本轮没有重跑前端浏览器联调。

## 7. 完成内容、限制与下一阶段

四项代码实现已覆盖：新券自动发布，成功确认与关闭释放，首次发布/长期 PREPARING/ACCEPTED 恢复，以及积压/隔离监控和受控重投。
数据库永久损坏、Redis 丢失且多项事实互相冲突时不会猜测补库存；保留请求、任务、冲突审计及暂停状态，需人工核对。

尚未覆盖的入口与明确限制：

- SQL 直插、通用 save 与历史 UNMANAGED 券不自动发布。创建券 HTTP 本身未新增客户端幂等键，响应完全丢失时先定位原券。
- 不扫描所有终态请求来修补被人工删除的历史确认/释放任务；正常流程的任务与业务终态由数据库事务一起提交。
- 自动恢复不替换坏 payload，不自动重新开放 BLOCKED，不实现已有活动 Redis 全量重建。
- 维护暂停和重建的后续核对顺序：停止受理并取控制行排他锁，处理 PREPARING，核对订单与有效预占，
  以数据库库存减尚未落库的有效预占计算新可用库存，重建用户归属/预占/墓碑后再发布新代次；不得只 SET 库存。
  本段是后续维护协议边界，未实现在线重建执行器。
- 已创建订单不得由本次关闭接口取消；支付、退款和支付超时处理未纳入。
- 无外部告警平台、Broker 精确积压指标和完整生产角色权限体系；运维入口以已有登录及独立令牌保护。
- 后台扫描和健康统计的生产容量尚待压测评估；默认批次及线程不代表生产吞吐承诺。
- 第五阶段继续负责真实故障压测、旧 Stream 未读和 pending 排空、停旧入口、正式切换、生产验收及回滚演练。

本轮只交付第四阶段，不进入第五阶段；由用户统一验收。
新增阶段文档被项目既有忽略规则命中，文件已落盘且索引已链接；未修改 `.gitignore`。
未来提交时须显式纳入，例如从项目根目录执行 `git add -f docs/optimization/order/ROCKETMQ_PHASE4.md docs/optimization/order/ROCKETMQ_PHASE4_1.md`。
当前没有执行暂存、提交或推送。

## 8. 方法注释可读性调整（2026-10-25）

本次只调整第四阶段相关方法和 Lua 的说明，不改变数据库事务、锁顺序、状态条件、SQL 或脚本写入行为。
此前“有界处理”“核对业务标识”“恢复资格”等表述需要先熟悉实现才能理解；现在先说明业务动作，再交代条件、结果和失败去向。

### 涉及文件及说明重点

以下文件路径均相对于 `backend/src/main/`。

| 文件 | 本次注释说明的具体内容 |
|---|---|
| `java/com/hmdp/service/ISeckillMaintenanceService.java` | 后台确认、关闭后的回补、原请求恢复、重投和隔离处置分别做什么，返回计数不等于成功数量 |
| `java/com/hmdp/service/impl/SeckillMaintenanceServiceImpl.java` | 逐个解释任务领取、锁内执行、恢复、暂停、消息构造及校验方法；失败登记下次时间，不当场重试 |
| `java/com/hmdp/infrastructure/seckill/SeckillReservationStore.java` | 预占是先暂扣 Redis 库存；确认不再扣库，释放最多回补一次，代次是库存版本号 |
| `java/com/hmdp/service/IVoucherService.java`、`java/com/hmdp/service/impl/VoucherServiceImpl.java` | 创建券与开放活动分两次提交，初始化失败保留原券编号；私有方法是否写 Redis、是否提交事务 |
| `java/com/hmdp/mapper/SeckillMaintenanceMapper.java` | 每条查询/更新操作哪张记录、是否加锁、什么条件匹配，以及 0、null、空集合各代表什么 |
| `java/com/hmdp/mapper/SeckillCampaignControlMapper.java` | 活动共享/排他锁保护发布、受理和补偿的顺序，锁不会在 Lua 返回后提前释放 |
| `java/com/hmdp/controller/SeckillMaintenanceController.java` | 运维身份来自登录上下文；关闭成功只代表状态和释放任务提交，不代表库存已回补 |
| `java/com/hmdp/infrastructure/seckill/SeckillMaintenanceJob.java` | 三类调度分别执行确认释放、恢复和指标告警；单轮失败不会取消后续周期 |
| `java/com/hmdp/infrastructure/mq/OrderMessageFailureStore.java` | 保存有界原文和摘要、重复隔离不覆盖处置，事务提交后才允许确认消费 |
| `java/com/hmdp/infrastructure/mq/RocketMqOrderListener.java`、`java/com/hmdp/infrastructure/mq/RocketMqDeadLetterListener.java` | 建单成功、隔离成功和 Broker 重试的区别；死信不会自动关闭请求或回补库存 |
| `resources/lua/seckill/initialize-campaign.lua` | 区分输入库存与已有库存，明确 PTTL=-1 表示没有过期时间，而非“瞬时键” |
| `resources/lua/seckill/finish-reservation.lua` | owner 是用户归属的订单号；只有 RESERVED 释放会加库存，REJECTED 只记录零数量终态 |
| `resources/lua/seckill/verify-reservation.lua` | 四键、身份、代次和数量的只读检查；库存为 0 仍可能持有合法预占 |

### 关键注释前后对比

维护入口原说明：

```java
/**
 * 有界处理确认/释放任务，租约失败或中断时保留待办。
 */
```

当前说明先交代实际动作和返回计数：

```java
/**
 * 执行数据库中待处理的预占确认和释放任务。
 * 成功订单的预占改为 CONFIRMED，不再扣库存；已关闭且未建单的请求释放预占，最多回补一份。
 * 每轮最多领取配置的批量数。失败时尝试登记下次执行时间；失去租约后不覆盖他人状态，未确认任务可到期接管。
 *
 * @return 本轮领取的任务数；0 表示未领到任务，计数包含执行失败的任务
 * @throws IllegalStateException 调用方已有事务或入口线程已中断
 */
```

任务异常处理原说明是“记录失败并重试”，当前说明明确实际只登记后续时间：

```java
// 这里只登记下次执行时间，不当场重试；已写入 Redis 的结果由后续幂等调用核对。
```

Lua 原说明把 `owner ~= orderId` 称为“用户 ID 不匹配”，现改成用户归属的订单号检查，并解释加库存的原因：

```lua
-- RESERVED 说明之前确实暂扣过一份；现在释放该份库存，首次才执行加一。
-- 核对用户归属的订单号、预占数量和加一上限，防止归还其他订单的库存或数值溢出。
-- 归还本订单之前暂扣的一份库存，再移除已核对属于该订单的用户购买资格。
```

### 本轮验证

- 12 个 Java 文件共 88 处方法说明已逐个检查，参数标签与实际声明逐项对应；非 void 方法有返回值说明，void 方法不写返回值标签。
- 修正本轮整理过程中发现的注释错位及参数标签错误，用户反馈 IDE 报错已消失。
- 对这 12 个 Java 文件和 3 个 Lua，去除注释后与本轮修改前快照逐个比较，所有可执行内容一致，包括 SQL 文本和 Lua 操作。
- 从项目根目录执行 `mvn -o -f backend/pom.xml -DskipTests test-compile`，主代码和测试代码编译通过。
- 从项目根目录执行 `mvn -o -f backend/pom.xml '-Dtest=*Test' test`，172 项现有单元、MVC、组件注册和模拟客户端测试全部通过，0 失败、0 错误、0 跳过。未新增测试，未重跑真实 MySQL、Redis 或 Broker 联调；日志位于 `/private/tmp/hm-dp-comment-tests.log`。
- 方法说明增加行数后同步更新配套流程图中的源码行号链接，原有 Mermaid 图和业务分支不变。
