# 订单 RocketMQ 第五阶段：启用新路径与故障验收

日期：2026-10-25。

第四阶段及命名、注释规则已提交为 `58ba8cdd`，未推送。本阶段代码留在工作区供审阅。
按用户最新要求，后续只使用新路径，不再实现旧 Stream 的接管、排空、历史券导入或回退切换。
已有旧实现及历史说明保留，旧 Controller 和消费者不再注册为 Spring 组件，历史消息和库存未删除。

## 1. 业务问题、原行为和本阶段范围

第四阶段后端已经可以自动发券、可靠受理、幂等建单和执行确认/释放，但默认 React 页面仍使用旧接口。
旧 `VoucherOrderServiceImpl` 还会在 Spring 启动时运行 Stream 消费线程，Redis 健康检查会创建旧消费组。
例如新券只初始化了新命名空间，页面却请求旧接口，就不能完成预期的新链路购买。

本阶段把默认页面固定到 `/voucher-order/reliable`，停用旧入口和消费者注册，增加暂停接单及启动配置检查。
继续沿用第四阶段的新券自动初始化和后台任务，不添加第二套受理或补偿服务。
历史券和历史 Stream 的迁移不在此次实现范围；新联调应通过 `POST /voucher/seckill` 创建新券。
未执行真实业务库迁移、应用部署或历史消息处置。

本阶段还修复恢复任务的一处并发误判：

1. 恢复线程 A 扫描到一个过期的 `PREPARING` 候选。
2. 客户端线程 B 重试原请求，成功提交 `ACCEPTED`；消费者随后提交订单和 `SUCCEEDED`。
3. 原实现 A 仍按旧快照判断，看到原订单已存在就报告“PREPARING 已有订单”，从而误暂停正常活动。
4. 当前实现先在活动共享锁、请求排他锁内重读账本。已推进的请求直接跳过；只有当前仍为 `PREPARING`
   且原订单存在时才报告冲突。后续 `accept` 仍独立取得自己的锁，不把检查事务跨到受理事务之外。

## 2. 涉及文件及职责

| 文件，相对于项目根目录 | 原职责 | 本阶段改动和业务作用 |
|---|---|---|
| `backend/src/main/java/com/hmdp/controller/VoucherOrderController.java` | 旧 Stream HTTP 入口 | 去掉 Controller 注册，标为历史参考；旧 URL 不再映射，也不转发到没有 requestId 的兼容入口 |
| `backend/src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java` | 旧预扣、Stream 消费和旧建单 | 去掉 Service 注册，保留实现和历史注释；因此原 `@PostConstruct` 不再随应用启动执行 |
| `backend/src/main/java/com/hmdp/config/SeckillOrderProperties.java` | 新组件及 API 配置 | 新增 `acceptingRequests`，只控制 HTTP 提交 |
| `backend/src/main/java/com/hmdp/controller/SeckillRequestController.java` | 新请求提交及归属查询 | 登录验证后检查接单开关；暂停时返回 503，不调用受理服务，查询继续可用 |
| `backend/src/main/java/com/hmdp/config/SeckillPipelineStartupCheck.java` | 原无对应文件 | 新增启动检查：开放 API 时必须同时启用可靠组件、MQ、Outbox、消费、死信隔离和维护任务，缺一即启动失败 |
| `backend/src/main/java/com/hmdp/service/impl/SeckillMaintenanceServiceImpl.java` | 第四阶段补偿与恢复 | 新增持锁重读 PREPARING 的内部步骤，修复扫描快照过时后误暂停活动；其他恢复、补偿继续沿用 |
| `backend/src/main/resources/application.yaml` | 开发环境配置 | 新增接单环境变量，更新当前唯一路径和命令超时说明 |
| `docker/redis/healthcheck.sh` | 连通检查及旧 Stream 建组 | 只保留 PING，不再通过健康检查创建订单流或消费组；不删除已存在的数据 |
| `frontend/web/src/api/vouchers.ts` | 新旧接口及页面开关 | 当前页面固定启用新路径，旧函数及页面参考分支保留；原环境变量不再启用旧路径 |
| `frontend/web/.env.example` | 前端环境示例 | 移除新链路选择开关，说明后端完整组件要求 |
| `frontend/web/playwright.seckill.config.ts` | 独立新链路浏览器测试 | 使用独立 5174 端口验证新抢购及商铺页面，无需业务选择开关，不复用已有开发服务 |
| `backend/src/test/java/com/hmdp/config/SeckillPipelineStartupCheckTest.java` | 原无对应文件 | 验证实际属性绑定、不完整配置阻止启动及组件扫描不注册旧类 |
| `backend/src/test/java/com/hmdp/controller/SeckillRequestControllerTest.java` | 新 API MVC 契约 | 验证暂停提交不写账本、原查询仍返回结果 |
| `backend/src/test/java/com/hmdp/infrastructure/mq/SeckillOutboxIT.java` | 前四阶段真实依赖验收 | 新增过时快照、两项真实连接超时、1000 用户竞争 100 份库存的四项用例 |
| `frontend/web/tests/seckill-order.spec.ts`、`tests/storefront.spec.ts` | 浏览器契约及商铺回归 | 默认执行新请求协议，验证保留 requestId、长编号及结果恢复；旧请求不得被页面调用 |
| 本文、文档索引、README、前端指南、购买指南和历史阶段衔接说明 | 项目说明 | 说明最新范围、前后对比、已验证项和未覆盖项，不把前阶段功能算作本阶段新增 |

本阶段没有新增数据库表或 Lua 业务脚本；第四阶段的三份 Lua 和数据库事务继续沿用。
曾在本轮起草、随后因范围调整而撤回的旧链路迁移代码没有保留为另一套入口。

## 3. 关键代码前后对比

### 3.1 当前界面固定使用新请求协议

修改前：

```typescript
export const reliableSeckillEnabled = import.meta.env.VITE_RELIABLE_SECKILL_ENABLED === 'true';
```

修改后：

```typescript
// 当前仅使用新链路；旧页面分支保留作参考，不再由环境变量启用。
export const reliableSeckillEnabled = true;
```

现有 `SeckillDialog` 在发送前保存 requestId，提交与重试都发送到
`POST /voucher-order/reliable/seckill/{voucherId}`；查询使用原 requestId。
旧源码保留方便查阅，但没有配置开关让当前页面转回旧入口。后端两个旧类去掉注册注解，
不是把旧接口静默转到新服务：旧客户端没有稳定 requestId，无法满足新协议的重试约定。

### 3.2 暂停接单，保留查询

原先没有独立暂停提交的判断。新增于 `SeckillRequestController.accept`：

```java
if (!seckillOrderProperties.isAcceptingRequests()) {
    return ResponseEntity.status(503)
            .body(Result.fail("秒杀入口暂时关闭，请保留原请求编号并稍后查询"));
}
```

这段在登录检查之后、调用 `requests.accept` 之前执行，因此本次被拒绝的提交不会创建账本或暂扣库存。
查询方法不检查此开关，后台恢复也不检查，已经受理的请求仍可到达终态。
修改环境变量后须重启实例；它不构成动态配置中心，也不能撤销已经进入受理方法的调用。

### 3.3 配置不完整时阻止 API 实例启动

原先各组件只按各自开关注册。新增 `SeckillPipelineStartupCheck`：

```java
if (!seckillOrderProperties.isReliableApiEnabled()) return;
if (!seckillOrderProperties.isReliableComponentsEnabled() || !rocketMqProperties.isEnabled()
        || !orderOutboxProperties.isEnabled() || !orderConsumerProperties.isEnabled()
        || !orderConsumerProperties.isDeadLetterEnabled() || !seckillMaintenanceProperties.isEnabled()) {
    throw new IllegalStateException("秒杀 API 要求启用可靠组件、MQ、Outbox、订单消费、死信隔离和维护调度");
}
```

API 关闭的独立工作实例可按原开关运行。检查覆盖的是配置完整性，不能证明 Topic 权限、Broker 可用性
或数据库结构健康；客户端生命周期、唯一索引检查及实际收发测试仍承担各自验证职责。
暂停提交也需要保留完整后台，以便收尾已受理请求。

### 3.4 恢复前重新核对当前状态

修改前直接根据扫描快照查询订单：

```java
if (orders.findOrder(candidate.getOrderId()) != null) {
    throw new SeckillConsistencyException("PREPARING 已有原订单，禁止重新预占");
}
```

修改后先调用新增 `isStillPreparing`，其内部在短事务中依次执行：

```java
campaigns.lockShared(candidate.getVoucherId());
SeckillRequest currentRequest = requireRequest(requests.lockByOrderId(candidate.getOrderId()));
if (currentRequest.getStatus() != SeckillRequestStatus.PREPARING) return false;
if (orders.findOrder(currentRequest.getOrderId()) != null) {
    throw new SeckillConsistencyException("PREPARING 已有原订单，禁止重新预占");
}
return true;
```

活动共享锁与发布、暂停的排他锁互斥；请求排他锁与受理及消费者互斥，使当前状态和订单检查位于同一保护区间。
返回 false 后跳过旧候选，不做重复受理或误暂停。锁在检查事务提交后释放，随后调用的 `accept`
仍按原逻辑再锁活动和请求，所以检查与受理之间状态变化也由受理自己的最新状态判断处理。
锁竞争立即失败后沿用第四阶段的恢复退避，不在持锁事务内等待重试。

## 4. 完整运行顺序及异常分支

1. 启动 API 实例：读取配置，各客户端按原条件注册；Bean 初始化结束时检查新管道开关完整性。
   旧 Service 和 Controller 不参与扫描注册，Redis 健康检查只 PING。
2. 发券：沿用第四阶段，事务 A 提交券、数据库库存和 PAUSED/PENDING；事务 B 持活动排他锁准备
   Redis stock/meta，成功后提交 READY/PUBLISHED。Redis 失败不重新创建券，由原券初始化或后台恢复继续。
3. 抢购：前端先按用户和券保存 requestId，HTTP 鉴权后检查是否接单；开放时沿用两个受理事务。
   第一事务提交 PREPARING；第二事务依次锁活动共享、请求排他，Lua 暂扣后提交 ACCEPTED 与 CREATE_ORDER。
4. 异步建单：Outbox 领取租约并提交后才发送 MQ；正式监听器调用原建单服务。
   数据库事务仍按活动共享锁、请求排他锁顺序核对，条件扣库、插订单、SUCCEEDED 和确认任务一起提交。
   提交后才 ACK；重复消费读取已有终态，不再次扣库。
5. 后台收尾：成功订单确认预占不再扣库存；明确关闭且无原订单的请求释放预占，首次释放最多回补一份。
   Redis 或数据库响应未知时保留持久任务；租约和幂等脚本负责恢复，不因超时推断请求已取消。
6. 客户端超时、404 或刷新：保留原 requestId，查询或重试原请求；没有明确终态不自动生成另一编号。
   接单暂停时提交返回 503，查询、消息消费、补偿和已持久化 PREPARING 的恢复仍可继续。
7. 后台扫描：PREPARING 候选先持锁重读，已推进则跳过；仍需恢复的请求沿用原订单及跟踪号。
   ACCEPTED 原事件恢复和一致性冲突暂停逻辑沿用第四阶段，不能根据扫描快照覆盖最新终态。

本轮没有变动商铺布隆同步、商铺缓存或它们的锁顺序；秒杀流程中没有新增布隆或缓存清理步骤。
线程中断、提交失败、组件未就绪等仍按原接口保留账本和任务，不新增“中断即自动重试”或“失败即回补”的行为。

## 5. 配置与本地验收

API 实例要求以下开关均为 true；当前 `application.yaml` 默认已开启：

| 环境变量 | 作用 |
|---|---|
| `SECKILL_RELIABLE_COMPONENTS_ENABLED` | 注册受理、建单、预占与维护业务组件 |
| `SECKILL_RELIABLE_API_ENABLED` | 注册新抢购及查询接口 |
| `ROCKETMQ_ENABLED` | 注册 MQ 客户端 |
| `ORDER_OUTBOX_ENABLED` | 投递持久化建单事件 |
| `ORDER_CONSUMER_ENABLED` | 正式订单消费者 |
| `ORDER_DEAD_LETTER_ENABLED` | 持久化隔离当前订单组死信 |
| `SECKILL_MAINTENANCE_ENABLED` | 发布恢复、请求恢复、确认释放及监控调度 |
| `SECKILL_ACCEPTING_REQUESTS` | 默认 true；false 仅暂停 HTTP 提交，修改后重启生效 |

运维接口仍默认关闭，启用须独立令牌和登录；未新增远程发信或自动告警服务。
API 关闭的专用工作实例可以仅运行需要的后台组件。
运行前按顺序在目标库手动执行已有 02、03、04、05 四份订单迁移；本阶段不增加迁移，不自动操作业务库。
已有历史重复订单会阻止唯一索引迁移，不能靠关闭检查绕过。

前端在 `frontend/web/` 目录执行 `npm run dev` 即使用新路径，无需 `VITE_RELIABLE_SECKILL_ENABLED`。
新券创建和原请求查询按 [购买联调指南](ROCKETMQ_PURCHASE_TEST.md) 操作。
该指南中第三阶段手工造数的内容保留为历史演练，当前推荐使用第四阶段的接口自动发券。

在项目根目录执行后端验收，测试需要本地 MySQL/Redis；Broker 地址可由 `ROCKETMQ_NAMESERVER` 覆盖：

```bash
mvn -f backend/pom.xml -Dtest='*Test' test
SECKILL_OUTBOX_IT=true ROCKETMQ_SMOKE_TEST=true \
  mvn -f backend/pom.xml -Dtest=SeckillOutboxIT test
```

集成测试只建立随机测试库、随机券 Redis 键和测试消费组；本次没有停止共享 Redis、MySQL 或 Broker。
Broker 测试使用隔离测试 Topic 和随机组，测试消息按 Broker 保留策略存续，不清理集群历史。

浏览器验收在 `frontend/web/` 目录执行，使用独立本地端口及模拟 HTTP 响应：

```bash
npm run build
npx playwright test --config=playwright.seckill.config.ts
```

## 6. 验证结果与具体边界

2026-10-25 本轮结果：

| 验证 | 结果 | 证据范围 |
|---|---|---|
| 后端单元、MVC、配置测试 | 177 项通过 | 包括暂停提交、配置缺失拒绝启动、旧组件不注册；不等于真实依赖联调 |
| 订单集成测试 | 71 项通过，无跳过 | 真实 MySQL/Redis；其中 3 项同时使用真实 Broker 验证 Outbox 投递、正式监听器及死信隔离 |
| 新增千用户用例 | 1000 请求、并发线程 16、库存 100，最终 100 成功、900 拒绝 | 实际组件调用，重复消费两次并完成确认；不是 HTTP 或 Broker 性能基准 |
| 新增真实连接故障 | 2 项通过 | 使用独占、不响应 Redis 协议的 TCP 端口触发 Lettuce 连接超时；恢复正常客户端后原请求及确认任务完成 |
| 新增过时 PREPARING 快照 | 1 项通过 | 真实数据库最新状态为 SUCCEEDED；恢复跳过旧快照且活动保持 READY，无冲突审计 |
| 前端构建 | 通过 | TypeScript 检查和 Vite 生产构建 |
| 浏览器契约及商铺回归 | 14 项通过 | Chrome、模拟后端接口；涵盖原编号重试、刷新恢复、长编号、鉴权和商铺展示 |

千用户测试在停止提交和消费后检查：订单唯一、数据库库存非负且与 Redis 可用库存同为 0、
无 CLOSED 对应成功订单、无遗留 PREPARING/ACCEPTED、确认任务均 SENT。
测试中的 CREATE_ORDER 直接交正式建单服务，未经过本用例的 Outbox 发送；其他真实 Broker 用例另行验证该段，
不能把这些分项结果写成“1000 个真实 HTTP 请求全程经 Broker 压测通过”。

本轮完整后端回归先有一次未打开 Broker 测试开关，3 项被跳过；随后用正确开关重新运行全部 71 项集成测试，
无跳过。177 项单元测试与最终 71 项集成测试合计覆盖 248 项后端测试，均通过。
Redis 连接超时验证没有重启 Redis 服务，也没有模拟 Lua 已执行后的真实网络丢包；后者沿用第四阶段的确定故障注入用例。
真实 Broker 死信用例采用测试专用单次立即转死信方式，不代表完整等待生产 16 次退避重试的长时间验收。

未执行的演练包括：实际结束 JVM 并重启、真实重启 Broker/Redis/MySQL、跨多实例的网络分区、
长时间 HTTP 压测及环境容量基准。在线 Redis 丢失后的受控全量重建也未新增；当前冲突仍暂停并保留账本，
不能按数据库剩余库存直接覆盖有在途预占的新命名空间。

## 7. 本阶段边界与后续验收

当前完成新路径默认启用、旧组件停用、暂停提交、启动配置检查、并发恢复误判修复以及上述故障与并发验证。
本阶段没有恢复旧 Stream、迁移历史券或旧消息，没有清空任何历史 Redis 数据，也未部署运行中的应用。
用户后续只使用新路径的要求优先于早期“先排空旧 Stream 再正式切换”的计划；历史消息不计入新链路恢复任务。

需要暂停时关闭新提交，保持投递、消费和维护运行，确认原请求终态及确认/释放任务收尾。
代码回退也只能回退到兼容当前账本的新链路版本，不能仅恢复旧接口或旧消费者注册。
继续验收前应先确认实际应用已重启加载本阶段代码、数据库迁移完成、新券已自动 READY，
再进行真实 HTTP 联调和需要独立环境的停进程、重启中间件演练。
