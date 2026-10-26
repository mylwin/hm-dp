# 订单 RocketMQ 第四阶段第 1 步：新秒杀券自动发布

后续：用户已追加授权一次完成第四阶段；确认/释放、后台恢复、隔离及运维入口的当前实现见
[第四阶段完整交付](ROCKETMQ_PHASE4.md)。下文保留本步首次交付时的行为与测试记录。

日期：2026-10-25。本步仅实现自动初始化活动，完成后停下单独验收。
第四阶段其余步骤及第五阶段切换均未实施。历史记录见 [第三阶段](ROCKETMQ_PHASE3.md)。

## 1. 业务问题、修改前行为与范围

原 `POST /voucher/seckill` 在数据库事务内保存两张券表，再写旧键
`hmdp:seckill:stock:<voucherId>`。新受理链路需要的控制行、`stock/meta` 均不存在。
因此接口返回了券编号，不代表该券已经能通过新接口购买；请求会停在 PREPARING，等待活动就绪。

例如创建库存 10 的新券 31：此前还要手动插入 PAUSED 控制行、执行 Redis 初始化命令，再更新 READY。
本步把这些步骤接入既有发券业务；成功返回后，新请求可以直接预占，库存由 10 变 9。
消息投递和建单继续沿用第二、三阶段实现。

不能直接把数据库当前库存 SET 到 Redis：请求 A 已预占一份，Redis 为 9，数据库建单尚未完成仍为 10；
请求 B 再次初始化若写回 10，就把 A 的预占覆盖了。即使数据库已经扣到 9，也可能仍有其他未建单预占。
所以已发布活动只核对，绝不按数据库库存修补或重建 Redis。

还必须区分首次发布中的暂停与维护暂停：历史券可能已经销售，也可能存在旧 Stream 未处理请求。
不能因为它当前是 PAUSED、Redis 键恰好不存在，就重新按全量库存发布。

范围：由后端接口创建的全新秒杀券；首次发布失败后的原券重试；必要的迁移和测试。
不自动接管历史券，不增加启动扫描、活动重建、主动关单、确认释放、恢复扫描、死信治理或正式切换。

## 2. 文件职责与修改原因

以下路径均相对于项目根目录。

| 文件 | 原有职责 | 本步改动与业务作用 |
|---|---|---|
| `backend/src/main/java/com/hmdp/service/IVoucherService.java` | 商铺券查询、发券契约 | 补齐 Javadoc；新增原券首次发布重试契约，说明时间和失败语义 |
| `backend/src/main/java/com/hmdp/service/impl/VoucherServiceImpl.java` | 保存券和旧库存 | 新增两段短事务、活动发布、条件重试和中断检查；原保存步骤封装为私有方法；相关依赖采用 final 构造器注入 |
| `backend/src/main/java/com/hmdp/controller/VoucherController.java` | 接收发券和查询请求 | 发券发布未确认时返回失败及原券编号；增加初始化重试入口，仍只调用 Service |
| `backend/src/main/java/com/hmdp/mapper/SeckillCampaignControlMapper.java` | 受理和建单取共享锁 | 新增 PENDING 插入、排他锁、条件开放 SQL，锁覆盖 Redis 核对及 READY 提交 |
| `backend/src/main/java/com/hmdp/entity/SeckillCampaignControl.java` | 活动代次与开放状态 | 增加 publicationStatus，区分新券首次发布与历史/维护状态 |
| `backend/src/main/java/com/hmdp/infrastructure/seckill/SeckillReservationStore.java` | 新 Redis 预占脚本适配 | 新增初始化/只读核对方法；统一复用现有 hash tag 键构造及 Lua 加载方式 |
| `backend/src/main/resources/lua/seckill/initialize-campaign.lua` | 原无此脚本 | 原子创建全新 stock/meta 或核对已有数据，冲突时不覆盖、不补键 |
| `backend/src/main/java/com/hmdp/exception/SeckillCampaignPublicationPendingException.java` | 原无此异常 | 携带已经提交的券编号，避免把发布失败误解为应该重新发券 |
| `backend/src/main/resources/db/migration/V20261025_04__seckill_campaign_publication.sql` | 原无此迁移 | 增加发布状态列；历史行默认 UNMANAGED；重复执行不修改任何已有发布状态 |
| `backend/src/test/java/com/hmdp/service/impl/VoucherServiceImplTest.java` | 原无此测试 | 模拟事务、Redis 和 Mapper，验证业务分支与提交调用顺序 |
| `backend/src/test/java/com/hmdp/controller/VoucherControllerTest.java` | 原无此测试 | 验证待确认响应保留原券编号，重试不重新发券；直接调用控制器，非真实 HTTP 联调 |
| `backend/src/test/java/com/hmdp/config/OrderPipelineConfigurationTest.java` | 条件注册测试 | 将实际发券服务加入上下文，验证可靠组件关闭时也可注册，不强制注入不存在的 Redis 组件 |
| `backend/src/test/java/com/hmdp/infrastructure/mq/SeckillOutboxIT.java` | 隔离数据库、Redis、MQ 集成测试 | 新增自动发布及故障用例；使用 MyBatis-Plus 工厂以执行真实 BaseMapper 发券操作；保留原测试 |
| `backend/src/test/resources/db/seckill-order-tables.sql` | 隔离测试业务表 | 券表补上自增和时间字段，支持真实发券服务及数据库生成编号 |
| 本文、`docs/README.md`、购买指南及第三阶段衔接说明 | 阶段记录和索引 | 仅文档调整；标明自动接口与历史手动初始化的适用范围 |

没有新增只负责转调的 Service。发券和发布仍由 `VoucherServiceImpl` 组织；Redis 协议在现有基础设施组件中实现。
没有调整配置开关及其默认值，沿用 `hmdp.seckill.reliable-components-enabled`。
通过 `ObjectProvider` 按需取得有条件注册的预占组件，可靠组件关闭时发券服务仍能运行。

沿用第二阶段：新命名空间、预占 Lua、请求账本、Outbox、共享锁和稳定订单号。
沿用第三阶段：数据库幂等建单、结果查询、确认/释放任务登记。
以上能力不计作本步新增，确认/释放任务执行器仍不存在。

## 3. 关键代码修改前后

### 3.1 发券：一个数据库事务变为先落库、再发布

修改前（关键语句）：

```java
@Transactional
public void addSeckillVoucher(Voucher voucher) {
    save(voucher);
    // 构造并保存 SeckillVoucher
    seckillVoucherService.save(seckillVoucher);
    stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucher.getId(),
            String.valueOf(voucher.getStock()));
}
```

修改后，可靠组件开启分支（省略输入和组件校验）：

```java
transaction().executeWithoutResult(status -> {
    saveSeckillVoucher(voucher);
    requireOne(campaigns.insertPending(voucher.getId()));
});
try {
    initializeSeckillCampaign(voucher.getId());
} catch (RuntimeException ex) {
    throw new SeckillCampaignPublicationPendingException(voucher.getId(), ex);
}
```

- `saveSeckillVoucher` 保存基本券及数据库库存，检查两次保存是否成功；失败抛异常回滚。
- `insertPending` 插入 `epoch=1, status=PAUSED, publication_status=PENDING`，与两张券表一起提交。
- 第一次 `executeWithoutResult` 返回时数据库提交已经完成，才进入初始化。
- 初始化阶段出错只回滚第二个事务；原券和初始库存仍在，异常携带同一券编号。
- 可靠组件关闭时仍在事务内保存原两张表和旧库存键，不创建新控制行或新命名空间。

新代码明确拒绝外层事务，避免独立事务已经开放活动、外层却还能回滚的误导。
两段事务均为 REQUIRES_NEW、READ_COMMITTED，使用已有 `transaction-timeout-seconds`，默认 5 秒。
旧分支也使用同一模板，因此新增了独立事务和输入校验限制；其 Redis 写入仍在数据库提交前，原有跨存储非原子边界未扩展修复。

### 3.2 发布状态和排他锁：新增

```sql
INSERT INTO tb_seckill_campaign_control(voucher_id, epoch, status, publication_status)
VALUES (?, 1, 'PAUSED', 'PENDING');

SELECT voucher_id, epoch, status, publication_status
FROM tb_seckill_campaign_control WHERE voucher_id = ? FOR UPDATE NOWAIT;

UPDATE tb_seckill_campaign_control
SET status = 'READY', publication_status = 'PUBLISHED', updated_at = UTC_TIMESTAMP(3)
WHERE voucher_id = ? AND epoch = ? AND status = 'PAUSED' AND publication_status = 'PENDING';
```

| 数据库状态 | 初始化重试行为 |
|---|---|
| PAUSED + PENDING | 可创建完全不存在的 Redis 键，或核对上次已创建的数据；成功后开放 |
| READY + PUBLISHED | 只核对现有 Redis；不创建、不重置、不再次更新 READY |
| UNMANAGED、已发布后 PAUSED、其他组合 | 拒绝，不接管或重新开放 |
| 无控制行 | 拒绝，不根据已有库存表自动创建控制行 |

排他锁保护的是“该券发布资格、代次和开放过程”，不是一份库存。
受理和建单原先的 `FOR SHARE NOWAIT` 与它互斥。发布线程从取锁直到数据库提交/回滚一直持锁，
不会在 Redis 调用结束后提前释放。并发重试取不到锁即失败，调用方稍后沿用原编号重试。
没有引入 Redis 分布式锁或缓存锁，不涉及 `RedisCacheClient`。

### 3.3 Redis 初始化协议：新增

```lua
if stockType == 'none' and metaType == 'none' and usersType == 'none' then
    if allowCreate ~= '1' then return 0 end
    redis.call('SET', stockKey, stockText)
    redis.call('HSET', metaKey, 'ready', '1', 'epoch', epoch, 'beginAt', beginAt, 'endAt', endAt)
    return 1
end
```

前三个键仍为 `hmdp:seckill:{31}:stock`、`:meta`、`:users`，同一 hash tag，全部通过 KEYS 传入。
Java 传入数据库持久化后重新读取的库存和时间，不直接复用可能有小数秒的 HTTP 入参。
项目 JDBC 约定 UTC；LocalDateTime 明确按 UTC 转为 Unix 毫秒，与预占脚本的 Redis TIME 比较。
新接口的无时区时间字符串也按 UTC 使用；不是按服务器本地时区猜测。

只在三键全部不存在且数据库仍为首次发布中的活动时写入；元数据没有 TTL。
Redis `ready=1` 此时仅表明键已准备好；数据库尚未 READY，受理共享锁仍被排他锁阻挡。
已有键分支只读：检查类型、无过期时间、ready、epoch、开始/结束时间和非负整数库存。
PENDING 重试还要求库存等于数据库初始值、users 不存在；否则不能认定它仍是从未使用的新活动。

READY 重试不比较 Redis 库存和数据库库存，因为未建单预占会使二者正常地暂时不同。
例如 Redis=9、DB=10 时重复初始化仍保持 Redis=9。已发布活动全部丢键也拒绝创建。
部分丢键、错误类型、错误代次、时间冲突或 TTL 均报错，不在此流程补齐。

Lua 原子执行隔离其他命令，但运行错误不自动回滚已写入语句；若极端错误造成部分键残留，
后续调用会拒绝，不会把部分状态当作就绪。脚本未知返回值同样不能开放数据库 READY。

### 3.4 返回原券编号与重试：新增

```java
try {
    voucherService.addSeckillVoucher(voucher);
} catch (SeckillCampaignPublicationPendingException ex) {
    return new Result(false, ex.getMessage(), ex.getVoucherId().toString(), null);
}
```

正常响应继续返回券编号。发布未确认响应 `success=false`，`data` 为原券编号字符串，例如 `"31"`；
不能因为此响应就重新 POST 新券。请求 `POST /voucher/seckill/31/initialize` 重试原活动即可，
成功时返回原券编号，业务服务不会重新保存两张券表。

“待确认”不保证状态一定 PAUSED：若 READY 提交成功但确认响应丢失，数据库可能已开放；
原券重试会进入已发布核对分支。该异常名称及响应不把未知结果误报为“确定未发布”。
初始化重试异常沿用现有全局异常处理返回失败；调用方已知路径中的券编号。

## 4. 完整流程及实际失败分支

1. 校验新券编号为空、库存非负、开始/结束时间有效；按数据库秒精度仍须开始早于结束。
2. 校验事务超时配置、独立调用条件及中断；可靠组件开启时先确认 Redis 组件已注册。
3. 事务 A 保存券、数据库库存、PAUSED/PENDING 控制行；成功提交后进入下一步。
4. 事务 B 取活动排他锁，确认发布状态，重新读取数据库库存和时间。
5. 检查中断，调用 Lua 创建或核对；返回后再次检查中断。
6. 首次发布条件更新 READY/PUBLISHED，事务 B 提交，然后数据库释放排他锁。
7. 发券接口返回成功；后续受理先取共享锁，核对 READY，按相同 epoch 调用原预占 Lua。
8. 原链路继续提交 ACCEPTED + CREATE_ORDER Outbox，MQ 消费建单并登记确认/释放任务。

本步没有商铺布隆同步、空值缓存或缓存清理；不得将其他优化阶段的这些流程算入本步。

| 分支 | 实际结果和下一步 |
|---|---|
| 正常完成 | 两个数据库事务已提交；Redis stock/meta 已准备，READY/PUBLISHED，接口成功 |
| 事务 A 保存失败 | 券、数据库库存、控制行全部回滚；不调用 Redis 初始化 |
| A 已提交、进程在 B 前退出 | 留下 PAUSED/PENDING；拿到原券编号后调用初始化重试；无自动扫描 |
| Redis 不可用、超时或响应未知 | B 回滚；通常保持 PAUSED/PENDING；Redis 可能已写入，重试只核对、不先删除 |
| Redis 成功，READY SQL 或 B 提交前失败 | B 回滚到 PAUSED/PENDING；Redis 键保留，可用原编号核对后开放 |
| READY 提交确认丢失 | 可能已提交，也可能未提交；重试根据数据库持久化状态决定创建资格 |
| 排他锁竞争 | NOWAIT 立即失败，没有“等锁超时后继续”的分支；原券稍后重试 |
| SQL/事务超时 | 抛异常并回滚当前事务；不把初始化未确认当作发布成功 |
| 中断 | 入口已中断时不写；Redis 调用后观察到中断则不执行 READY，回滚 B，保留中断标记；不自动重试 |
| 开关开启但组件未注册 | 创建之前失败，不退回旧 Redis 库存；重试也失败 |
| 开关关闭 | 创建走旧分支；新初始化重试入口拒绝，不依赖缺失的预占 Bean |
| 历史或维护暂停活动 | 拒绝自动发布，既有数据不改动 |
| 部分丢键/代次冲突 | 报错且不覆盖；是否需要暂停或恢复须由后续协议核对 |

数据库事务超时并不等于 Redis 网络命令被强制取消；网络调用还受 Redis 客户端超时控制。
Redis 超时后延迟到达的初始化命令仍受脚本全新键检查保护，不能覆盖后来已经被扣减的库存。

## 5. 为什么选择这个顺序

- **先持久化 PAUSED 再写 Redis**：应用退出后数据库仍有原券与发布资格，不需要重建编号。
- **Redis 成功后才开放数据库**：避免请求看到 READY，却发现新 Redis stock/meta 未准备好。
- **用活动排他锁协调**：沿用现有共享锁协议，避免发布、受理、消费之间出现交错；锁覆盖最终提交。
- **发布状态与 READY 分开**：READY 表示是否允许业务；publication_status 表示此入口是否有首次初始化资格。
- **重试不删除、不 SET 已有库存**：Redis 和数据库库存不同可能只是尚未消费的合法预占。
- **不默认恢复历史活动**：历史旧 Stream、已有订单及未知预占必须先核对；本步只解决新建入口。
- **两段事务留在既有 Service**：它们都是发券业务，私有方法足以复用保存、验证和事务配置。

## 6. 迁移、接口与单独验收

### 6.1 迁移和开关

从项目根目录执行，数据库选目标开发库；密码由客户端交互输入，不写在命令中：

```bash
mysql -h 127.0.0.1 -P 3306 -u root -p hm-dp \
  < backend/src/main/resources/db/migration/V20261025_04__seckill_campaign_publication.sql
```

要求先完成第二、三阶段迁移；脚本不会由应用自动执行。本次仅在随机隔离测试库执行，未修改开发业务库。
DDL 会隐式提交，应用前自行做好目标库备份；回滚代码可以保留新增列，不需要删除业务数据。
列默认 UNMANAGED，不将旧券、旧键复制到新链路。脚本重复执行不重置 PENDING/PUBLISHED。

新建券自动发布由现有 `SECKILL_RELIABLE_COMPONENTS_ENABLED=true` 控制。
当前开发 YAML 默认为 true，Java 属性本身默认 false，原开关含义及默认值未修改。
新 HTTP 受理、MQ 消费和 Outbox 仍需要各自既有开关；本步不修改前端默认配置。
验收购买须显式设置前端 `VITE_RELIABLE_SECKILL_ENABLED=true`，或直接使用新受理 API，
不能用旧 Stream 入口购买新链路自动发布的券。操作见 [购买指南](ROCKETMQ_PURCHASE_TEST.md)。

### 6.2 接口验收

用已登录会话调用 `POST /voucher/seckill`，请求示例（时间按 UTC；请按验收日期调整窗口）：

```json
{
  "shopId": 1,
  "title": "第四阶段自动发布验收券",
  "subTitle": "自动初始化新链路活动",
  "rules": "每人限购一张",
  "payValue": 5000,
  "actualValue": 10000,
  "type": 1,
  "status": 1,
  "stock": 10,
  "beginTime": "2026-10-25T00:00:00",
  "endTime": "2026-11-01T00:00:00"
}
```

1. 记下响应券编号：控制行为 READY/PUBLISHED、epoch=1；新 Redis stock=10，meta.ready=1，meta.epoch=1。
2. 用第三阶段新接口购买，确认 ACCEPTED，再通过结果查询检查建单；不得另外创建旧库存键。
3. 再调用 `POST /voucher/seckill/<原编号>/initialize`，观察 Redis 库存仍为预占后的值。
4. 故障验收在隔离环境执行：初始化不可用时保留原编号，恢复 Redis 后用同一初始化路径重试；不要重新发券。

两个写接口沿用现有登录拦截；没有新增管理员角色权限体系，也不声称具备生产运营后台权限控制。
已有普通券 POST 和查询业务未变。后端直接调用测试不等同完整浏览器/HTTP 联调。

### 6.3 自动化验证

从项目根目录执行；默认连接本机 MySQL/Redis，可按现有环境变量覆盖：

```bash
SECKILL_OUTBOX_IT=true mvn -o -f backend/pom.xml \
  '-Dtest=*Test,SeckillOutboxIT' test
```

测试只创建随机前缀数据库及测试券对应 Redis 键，用后清理，不启动旧 Stream 消费者。
实际 MQ 用例需要额外环境开关，本步不必依赖 Broker。

2026-10-25 最终回归：202 项，200 项通过，0 失败、0 错误，2 项真实 Broker 用例按环境开关跳过。
其中 163 项单元/模拟与上下文测试通过；集成测试共 39 项，37 项真实 MySQL/Redis 测试通过、2 项 Broker 测试跳过。
本步新增 12 项单元/控制器测试和 9 项集成测试，另扩展既有条件注册测试。未重新运行真实 Broker、完整 HTTP 或浏览器联调。
`git diff --check` 及变更文档的本地链接检查通过。新增阶段文档被项目现有忽略规则命中；
文件已完整落盘，未修改 `.gitignore`，未来提交时须显式指定该文件（例如 `git add -f docs/optimization/order/ROCKETMQ_PHASE4_1.md`）。
本次未提交或推送代码。

模拟测试覆盖事务调用顺序、数据库保存失败、Redis 失败后保留券编号、已发布只读核对、历史和维护暂停拒绝、
开关关闭走旧路径、组件缺失、非法资料、外层事务拒绝及中断标记。
真实 MySQL/Redis 测试覆盖自动发布后可受理、重复初始化保护已预占库存、Redis 响应丢失、READY 更新回滚、
已发布活动全部丢键不重建、部分键/代次/库存/TTL 冲突、事务 A 回滚、共享/排他锁竞争、重复迁移、历史/暂停拒绝及零库存。
响应丢失通过真实 Redis 执行后主动抛异常模拟；不是操作系统杀进程或实际网络丢包试验。

## 7. 已解决问题与后续边界

已解决：新建秒杀券不再需要手工准备活动控制和 stock/meta；发布未确认可沿用原券编号重试；
重复初始化不覆盖正在使用的库存；数据库与 Redis 代次按同一控制行核对。

尚未覆盖：

- SQL 直插券、MyBatis-Plus 通用 save、历史券迁移不自动触发发布。UNMANAGED 不能借重试入口接管。
- 首次创建接口没有新增客户端发券幂等键；事务 A 提交结果未知或整个创建 HTTP 响应丢失且未取得券编号时，
  应先查数据库识别原券，不能盲目重复发券。当前券创建不承诺整段 HTTP 的幂等性。
- PENDING 不定时扫描；应用退出后须用原编号重试。首次发布完成后的 Redis 丢失/损坏不自动恢复。
- 对已发布异常活动，失败核对不主动改成 PAUSED；现有预占在缺 stock/meta 时会拒绝，但完整异常隔离和重建属于后续恢复协议。
- 旧链路历史行为保留；新旧库存命名空间隔离不等于同券混流安全。前端默认旧入口，验收须明确使用新入口。
- 确认、释放任务仍 PENDING，成功订单的 Redis 预占仍 RESERVED；本步没有再次扣库存，也没有执行回补。
- 第 4.2 步将实现幂等确认与释放；第 4.3 步恢复长期 PREPARING/ACCEPTED；第 4.4 步处理积压、告警、死信与受控重投。
- 第五阶段才做故障压测、旧 Stream 排空、正式切换及回滚演练。

本步完成后停下供用户单独验收，不继续实现 4.2。
