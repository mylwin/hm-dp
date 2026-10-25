# 订单 RocketMQ 第四阶段：逐流程 Mermaid 图解

日期：2026-10-25。以下 16 张图按当前工作区实际代码绘制，包含本轮尚未提交的第四阶段实现。
实现说明与验收记录见 [第四阶段完整说明](ROCKETMQ_PHASE4.md)。本次只补充文档，不修改业务代码。
新增三个 Lua 的输入、返回值和脚本内部完整分支见 [Lua 专项 Mermaid 图解](ROCKETMQ_PHASE4_LUA_FLOWS.md)。

## 阅读约定

- `S 锁`：活动控制行共享锁，`FOR SHARE NOWAIT`。
- `X 锁`：行排他锁；活动、请求和指定事件的竞争使用 `NOWAIT`，冲突立即失败。
- `SKIP LOCKED`：领取任务时跳过其他线程正在锁定的行。
- 图中“提交后释放锁”均指 MySQL 事务持有的锁。Redis Lua 执行结束不代表数据库锁已经释放。
- Redis 网络超时表示执行结果可能未知；数据库回滚不能撤销已经完成的 Redis 写入。
- 本地确认/释放任务的 `SENT` 表示 Redis 处理已确认；`CREATE_ORDER/SENT` 表示 MQ 投递已确认。两者都不等于支付成功。
- “记录错误、退避”不表示当场自动重试。下一轮调度或原编号重试才会再次推进。
- 第四阶段不涉及商铺布隆同步和商铺缓存锁，不包含第五阶段旧 Stream 排空或正式切换。

## 流程目录

| 编号 | 流程 | 核心问题 |
|---|---|---|
| 01 | [创建新秒杀券](#flow-01) | 先保存什么，什么时候初始化 Redis？ |
| 02 | [初始化并开放活动](#flow-02) | 如何只首次创建，避免重置已有库存？ |
| 03 | [领取和执行本地任务](#flow-03) | 多实例如何领取、接管和确认任务？ |
| 04 | [确认成功订单的预占](#flow-04) | 如何确认而不再扣一次库存？ |
| 05 | [释放关闭请求的预占](#flow-05) | 如何最多回补一次并拦截迟到预占？ |
| 06 | [受控关闭未建单请求](#flow-06) | 如何避免关闭和消费者同时成功？ |
| 07 | [恢复未发布的新活动](#flow-07) | 应用退出后如何继续首次发布？ |
| 08 | [恢复长期 PREPARING](#flow-08) | Redis 可能扣过时如何恢复原请求？ |
| 09 | [恢复长期 ACCEPTED](#flow-09) | 如何核对事实并沿用原事件重投？ |
| 10 | [冲突暂停和审计](#flow-10) | 怎样暂停本代次而不误伤新代次？ |
| 11 | [消费普通消息并隔离非法协议](#flow-11) | 哪些消息可隔离后确认，哪些必须重试？ |
| 12 | [接收并持久化死信](#flow-12) | 何时能向 Broker 确认死信？ |
| 13 | [运维接口鉴权与分流](#flow-13) | 普通登录与运维权限如何区分？ |
| 14 | [人工受控重投](#flow-14) | 如何拒绝改 payload 或抢有效租约？ |
| 15 | [人工处置隔离待办](#flow-15) | 怎样记录无需重投，且保留消息证据？ |
| 16 | [健康监控与日志告警](#flow-16) | 哪些积压、错误和隔离情况会告警？ |

<a id="flow-01"></a>

## 01. 创建新秒杀券

入口：`POST /voucher/seckill`。本流程负责创建资料，实际 Redis 发布由流程 02 完成。

```mermaid
flowchart TD
    A["收到新秒杀券资料"] --> B["校验编号为空、库存非负、时间有效<br/>检查独立调用、配置及中断"]
    B --> C{"可靠组件是否开启？"}
    C -->|关闭| OLD["旧分支事务：保存券和数据库库存<br/>写旧 Redis 库存键，再提交"]
    OLD --> OLD_OK["返回券编号；不创建新链路控制行"]
    C -->|开启| D["确认新 Redis 组件已注册"]
    D --> T1["开始数据库事务 A"]
    T1 --> E["保存基本券和秒杀库存<br/>插入 epoch=1、PAUSED/PENDING 控制行"]
    E --> F["提交事务 A<br/>券及原编号已持久化"]
    E -->|写入失败| R1["回滚事务 A<br/>不进入 Redis 发布"]
    F --> G["调用流程 02：初始化并开放原活动"]
    G -->|已确认成功| OK["返回成功及券编号"]
    G -->|发布未确认| WAIT["返回失败及原券编号<br/>沿用此编号重试，不重新发券"]
    B -->|校验失败| FAIL["返回失败，停止创建"]
    D -->|组件缺失| FAIL
```

**顺序原因**：先提交 `PAUSED/PENDING`，进程退出后仍能找到原活动。发布阶段失败不能回滚已经提交的新券。
若事务 A 本身的提交结果未知，不能把它直接当作可重建的新券；当前创建接口没有新增客户端发券幂等键。

实现：[VoucherServiceImpl.addSeckillVoucher](../../../backend/src/main/java/com/hmdp/service/impl/VoucherServiceImpl.java#L69)、
[VoucherController](../../../backend/src/main/java/com/hmdp/controller/VoucherController.java)。

<a id="flow-02"></a>

## 02. 初始化并开放活动

调用方：新建券流程、`POST /voucher/seckill/{voucherId}/initialize`、后台首次发布恢复。

```mermaid
flowchart TD
    A["沿用原券编号初始化"] --> B["开始独立事务 B<br/>获取活动 X 锁"]
    B --> C{"持久化状态是否允许？"}
    C -->|PAUSED/PENDING| P["允许首次创建 Redis 键"]
    C -->|READY/PUBLISHED| V["只允许核对已有 Redis 键"]
    C -->|历史、BLOCKED、维护暂停或缺行| REJECT["拒绝自动接管或重新开放"]
    P --> D["重新读取数据库库存和 UTC 时间"]
    V --> D
    D --> E["检查中断<br/>执行初始化 Lua"]
    E --> F{"Lua 创建或核对通过？"}
    F -->|否：缺键、冲突或结果未知| RB["回滚事务 B，释放 X 锁<br/>保留已创建券及可能已写的 Redis"]
    F -->|是| G["再次检查中断"]
    G --> H{"原状态是 PENDING？"}
    H -->|是| I["条件更新为 READY/PUBLISHED"]
    H -->|否| J["不重复更新活动状态"]
    I --> COMMIT["提交事务 B 后释放 X 锁<br/>返回初始化已确认"]
    J --> COMMIT
    B -->|锁冲突或数据库异常| RB
    D -->|资料无效| RB
    G -->|中断| RB
    I -->|更新失败| RB
    REJECT --> RB
```

Lua 只在 `stock/meta/users` 三键全部不存在且允许首次创建时写入；已有键只读核对，不执行库存覆盖。
PENDING 重试要求库存仍为数据库初始值且没有用户归属。PUBLISHED 核对不要求 Redis 库存等于数据库库存，因为可能有未建单预占。
已发布活动缺键时也不能借此入口补建。

Redis `ready=1` 先于数据库 `READY` 写入，但受理需要数据库 READY 且活动共享锁不能越过当前排他锁。
提交确认丢失时活动可能已经开放，应以原编号重试查询实际状态，不能声称一定仍为 PAUSED。

实现：[initializeSeckillCampaign](../../../backend/src/main/java/com/hmdp/service/impl/VoucherServiceImpl.java#L111)、
[initialize-campaign.lua](../../../backend/src/main/resources/lua/seckill/initialize-campaign.lua)。

<a id="flow-03"></a>

## 03. 领取和执行本地确认／释放任务

本流程是确认和释放共用的任务骨架；具体 Redis 状态逻辑分别见流程 04、05。

```mermaid
flowchart TD
    A["定时执行 processReservationTasks"] --> B{"批次未满且线程未中断？"}
    B -->|否| END["结束本轮"]
    B -->|是| C["事务 A：SKIP LOCKED 领取到期任务<br/>只选 CONFIRM/RELEASE_RESERVATION"]
    C -->|无任务| END
    C --> D["发放新租约令牌、增加尝试次数<br/>提交事务 A"]
    D --> E["解析并核对任务外层标识"]
    E --> F["事务 B：活动 S 锁 → 请求 X 锁<br/>核对消息身份和代次 → 任务 X 锁"]
    F --> G{"仍持有有效租约？"}
    G -->|否| SKIP["不执行 Redis，结束事务<br/>未完成任务交有效持有者或过期接管"]
    G -->|是| H["核对数据库终态和原订单<br/>执行流程 04 或 05"]
    H --> I["检查中断<br/>以原租约标记任务 SENT"]
    I --> J["提交事务 B 后释放锁"]
    J --> B
    SKIP --> B
    E -->|异常| RETRY["当前执行事务回滚<br/>有效租约下登记退避及错误类型"]
    F -->|异常| RETRY
    H -->|异常| RETRY
    I -->|异常| RETRY
    RETRY --> K{"已知数据一致性冲突？"}
    K -->|是| PAUSE["调用流程 10：暂停与审计"]
    K -->|否| LOG["记录任务重试日志"]
    PAUSE --> LOG
    LOG --> B
```

若最终 `markSent` 因租约已过期更新 0 行，当前事务正常结束，任务仍留给下一轮接管；不能凭 Redis 已执行就覆盖新令牌。
若 Redis 已完成而数据库回执事务失败，重试仍执行原订单，依靠 Lua 幂等收敛。
数据库故障导致领取、退避或暂停也失败时，本轮可能提前结束；未确认任务仍由租约与下轮调度恢复。

实现：[processReservationTasks](../../../backend/src/main/java/com/hmdp/service/impl/SeckillMaintenanceServiceImpl.java#L62)、
[finishLocked](../../../backend/src/main/java/com/hmdp/service/impl/SeckillMaintenanceServiceImpl.java#L113)、
[SeckillMaintenanceMapper](../../../backend/src/main/java/com/hmdp/mapper/SeckillMaintenanceMapper.java)。

<a id="flow-04"></a>

## 04. 确认成功订单的预占

前提：流程 03 已核对消息、活动代次及有效租约，当前仍持有活动、请求和任务锁。

```mermaid
flowchart TD
    A["处理 CONFIRM_RESERVATION"] --> B{"账本为 SUCCEEDED<br/>且原订单编号、用户、券匹配？"}
    B -->|否| BAD["一致性冲突<br/>任务保留，转流程 10"]
    B -->|是| C["检查线程中断<br/>调用确认 Lua"]
    C --> D{"Redis 类型、库存值、meta 代次<br/>预占身份、数量和用户归属均有效？"}
    D -->|否| BAD
    D -->|是| E{"预占状态？"}
    E -->|RESERVED| F["写 CONFIRMED"]
    E -->|CONFIRMED| F
    E -->|缺失、RELEASED 或其他| BAD
    F --> G["不扣库存，不删除用户归属"]
    G --> H["返回 Lua 已完成<br/>回到流程 03 保存任务回执"]
    C -->|网络异常或执行结果未知| WAIT["保留原任务退避<br/>不把未知结果当成冲突"]
```

例子：Redis 库存已经因预占从 10 变 9，确认后仍为 9。重复写 CONFIRMED 没有第二次扣库。
进入 Lua 前后的中断会交任务骨架处理，保留中断标记，不返回“自动重试成功”。

实现：[finishLocked](../../../backend/src/main/java/com/hmdp/service/impl/SeckillMaintenanceServiceImpl.java#L113)、
[finish-reservation.lua](../../../backend/src/main/resources/lua/seckill/finish-reservation.lua)。

<a id="flow-05"></a>

## 05. 释放关闭请求的预占

前提：流程 03 已核对消息、代次和有效租约。此图覆盖释放 Lua 的各个状态分支。

```mermaid
flowchart TD
    A["处理 RELEASE_RESERVATION"] --> B{"账本 CLOSED<br/>且原订单不存在？"}
    B -->|否| BAD["数据冲突：拒绝回补<br/>保留任务并暂停核对"]
    B -->|是| C{"Redis 类型、库存、meta 代次<br/>已有预占身份均有效？"}
    C -->|否| BAD
    C -->|是| D{"预占状态？"}
    D -->|RELEASED| DONE["直接返回已完成<br/>不动库存或当前用户归属"]
    D -->|CONFIRMED 或未知状态| BAD
    D -->|RESERVED| E{"数量为 1、归属仍是本订单<br/>且库存加一不越界？"}
    E -->|否| BAD
    E -->|是| F["库存加 1<br/>删除属于本订单的用户归属"]
    F --> TOMB["写 RELEASED、quantity=0<br/>保留原用户、订单、券和代次"]
    D -->|REJECTED| G{"数量为 0<br/>用户归属不是本订单？"}
    G -->|否| BAD
    G -->|是| TOMB
    D -->|不存在| H{"原 PREPARING 被显式关闭<br/>且用户归属不是本订单？"}
    H -->|否| BAD
    H -->|是| TOMB
    TOMB --> DONE
    DONE --> RECEIPT["回到流程 03 保存任务回执"]
```

只有 RESERVED 分支执行库存加一。REJECTED 和允许缺失的 PREPARING 分支只写零数量墓碑，不猜测回补。
原 ACCEPTED 的预占丢失属于冲突，不能当作“从未扣过”静默完成。
旧订单已经 RELEASED 时不核删当前归属，避免删除同用户后来新订单的资格。
迟到的预占 Lua 看到 RELEASED 后返回终态，不再扣库存。Redis 网络异常仍走流程 03 的退避分支。

实现：[finish-reservation.lua](../../../backend/src/main/resources/lua/seckill/finish-reservation.lua)、
[reserve-order.lua](../../../backend/src/main/resources/lua/seckill/reserve-order.lua)。

<a id="flow-06"></a>

## 06. 受控关闭尚未建单的请求

入口：`POST /ops/seckill/orders/{orderId}/close`。先经过流程 13 的登录和独立令牌校验。

```mermaid
flowchart TD
    A["已鉴权的原订单号和关闭原因"] --> B["校验参数，读取账本定位活动"]
    B --> C["开始独立事务<br/>活动 S 锁 → 请求 X 锁"]
    C --> D{"请求仍为 PREPARING 或 ACCEPTED？"}
    D -->|否| FAIL["回滚并拒绝关闭"]
    D -->|是| E{"活动 READY 且原订单不存在？"}
    E -->|否| FAIL
    E -->|是| F["缺代次时固定当前代次<br/>再次核对代次一致"]
    F --> G["缺受理时间时补任务时间<br/>根据原状态设置关闭原因"]
    G --> H["同事务：更新 CLOSED<br/>插入原订单释放任务<br/>写 CLOSE 审计"]
    H --> I["提交后释放数据库锁<br/>返回关闭请求已提交"]
    I --> J["流程 03、05 异步执行释放<br/>此时才可能实际回补 Redis"]
    C -->|锁或数据库异常| FAIL
    F -->|代次冲突| FAIL
    H -->|任何写入失败| FAIL
```

关闭成功不代表当场已回补。与消费者竞争同一个请求锁时，只能有一个终态：消费者先成功，关闭被拒绝；
关闭先提交，迟到消费者读取 CLOSED 后不建单。此接口不取消已创建订单，也不处理支付或退款。

实现：[close](../../../backend/src/main/java/com/hmdp/service/impl/SeckillMaintenanceServiceImpl.java#L340)。

<a id="flow-07"></a>

## 07. 后台恢复未发布的新活动

```mermaid
flowchart TD
    A["定时 recoverDue"] --> B["限量扫描 PAUSED/PENDING<br/>且恢复尝试已到期的活动"]
    B --> C["短事务建立恢复记录<br/>原子领取，增加次数并写下一次时间"]
    C --> D{"领取成功且线程未中断？"}
    D -->|否| NEXT["跳过或停止本轮"]
    D -->|是| E["调用流程 02<br/>沿用原券编号首次发布"]
    E -->|成功| OK["活动 READY/PUBLISHED<br/>本次恢复结束"]
    E -->|失败| F["保存失败类型<br/>保留已登记的恢复退避"]
    F --> G{"已知数据一致性冲突？"}
    G -->|否| WAIT["记录恢复日志<br/>等待下一次到期"]
    G -->|是| H["新事务获取活动 X 锁"]
    H --> I{"当前仍为 PENDING？"}
    I -->|否| KEEP["不改已有新状态"]
    I -->|是| BLOCK["写 PAUSED/BLOCKED<br/>保存冲突审计"]
    BLOCK --> COMMIT["提交后释放 X 锁<br/>不再被首次发布扫描自动开放"]
    KEEP --> COMMIT
```

恢复资格在调用 Redis 前已持久化。进程退出后，到下一次恢复时间还能再次领取。
历史 UNMANAGED 和已标记 BLOCKED 的活动不在此扫描范围。锁或数据库故障使本轮暂停保存失败时，仍不能声称已成功隔离。

实现：[recoverDue 的活动分支](../../../backend/src/main/java/com/hmdp/service/impl/SeckillMaintenanceServiceImpl.java#L158)、
[claimRecovery](../../../backend/src/main/java/com/hmdp/service/impl/SeckillMaintenanceServiceImpl.java#L234)。

<a id="flow-08"></a>

## 08. 后台恢复长期 PREPARING 请求

```mermaid
flowchart TD
    A["扫描长期未更新的 PREPARING<br/>并领取 REQUEST 恢复资格"] --> B{"原订单号已存在数据库订单？"}
    B -->|是| CONFLICT["保留原请求<br/>记录冲突并转流程 10"]
    B -->|否| C["用账本原 userId、voucherId<br/>requestId、traceId 调用 accept"]
    C --> D{"重新读取的请求仍为 PREPARING？"}
    D -->|否| DONE["返回原持久化状态<br/>不再次预占"]
    D -->|是| E["独立受理事务<br/>活动 S 锁 → 请求 X 锁，再检查状态"]
    E --> F{"活动 READY 且请求仍需受理？"}
    F -->|活动未就绪| WAIT["回滚本次受理<br/>保留原请求及恢复退避"]
    F -->|请求已经推进| DONE
    F -->|是| G["调用原预占 Lua<br/>已 RESERVED 时复用原结果"]
    G -->|预占成功| H["同事务写 ACCEPTED<br/>及原 orderId 的 CREATE_ORDER Outbox"]
    G -->|明确业务拒绝| R["写 REJECTED 和原拒绝原因"]
    G -->|已知终态、归属或就绪数据冲突| CONFLICT
    G -->|网络异常或结果未知| WAIT
    H --> COMMIT["提交后释放锁<br/>保留原编号，返回已受理或已拒绝"]
    R --> COMMIT
    H -->|Outbox 写入或提交失败| WAIT
```

原受理事务先结束，恢复程序再记录失败或暂停，不能带着共享锁直接升级活动排他锁。
已存在的 PREPARING 账本是复用原编号的依据；不覆盖人工删除账本等破坏性修改场景。
图中第一次订单事实检查按候选 PREPARING 快照进入，`accept` 内部才会重新读取和加锁检查最新请求状态。

实现：[recoverDue 的请求分支](../../../backend/src/main/java/com/hmdp/service/impl/SeckillMaintenanceServiceImpl.java#L158)、
[accept](../../../backend/src/main/java/com/hmdp/service/impl/SeckillRequestServiceImpl.java#L132)、
[reserve-order.lua](../../../backend/src/main/resources/lua/seckill/reserve-order.lua)。

<a id="flow-09"></a>

## 09. 后台恢复长期 ACCEPTED 请求

```mermaid
flowchart TD
    A["长期 ACCEPTED 且恢复到期<br/>领取 REQUEST 恢复资格"] --> B["以 SYSTEM 调用原订单 replay"]
    B --> C["独立事务：活动 S 锁 → 请求 X 锁<br/>重新核对 ACCEPTED、READY 和同代次"]
    C --> D["核对原订单身份和 Redis 预占<br/>CONFIRMED 必须有匹配的原订单"]
    D --> E["从原账本构造预期消息<br/>锁定原 eventId 的 Outbox"]
    E --> F{"原 Outbox 情况？"}
    F -->|不存在| NEW["由原账本补建相同事件<br/>保持原编号、跟踪号和受理时间"]
    F -->|存在| CHECK{"类型及消息体与账本一致？"}
    CHECK -->|否| BAD["回滚，记录错误<br/>转流程 10 暂停核对"]
    CHECK -->|是| STATE{"任务状态？"}
    STATE -->|PENDING 或 SENDING| KEEP["直接结束当前事务<br/>原投递器继续发送或接管，不抢任务"]
    STATE -->|SENT| REPLAY["原事件重置 PENDING<br/>不更换 payload 或清零尝试次数"]
    NEW --> AUDIT["保存 SYSTEM/REPLAY 审计"]
    REPLAY --> AUDIT
    AUDIT --> COMMIT["提交后释放锁<br/>由原投递器和消费者继续完成"]
    D -->|已知事实冲突| BAD
    C -->|锁冲突、活动暂停或状态已变| WAIT["回滚本次恢复<br/>记录错误并退避，不自动关闭"]
    D -->|网络结果未知| WAIT
```

恢复不直接再次扣数据库库存。若原订单已经存在且身份一致，原消费者重投时核对订单、补齐成功状态和确认任务。
本流程仅对 CREATE_ORDER 的原事件操作；本地确认/释放任务由流程 03 处理。
正常的年龄阈值只触发核对和告警，不是关闭依据。

实现：[replayLocked](../../../backend/src/main/java/com/hmdp/service/impl/SeckillMaintenanceServiceImpl.java#L279)、
[verify-reservation.lua](../../../backend/src/main/resources/lua/seckill/verify-reservation.lua)。

<a id="flow-10"></a>

## 10. 请求或预占冲突后的活动暂停与审计

适用于请求恢复、原事件重投和本地任务执行发现的已知冲突。首次发布活动的专门重查分支见流程 07。

```mermaid
flowchart TD
    A["原业务执行事务已结束<br/>发现已知一致性冲突"] --> B["读取原请求快照，定位活动"]
    B --> C{"请求仍存在？"}
    C -->|否| MISSING["记录 requestMissing 错误<br/>无法据此确认对应活动已暂停"]
    C -->|是| D["另起事务<br/>活动 X 锁 → 请求 X 锁"]
    D --> E{"活动存在，且请求代次<br/>未固定或与活动代次一致？"}
    E -->|是| PAUSE["将该活动写为 PAUSED/BLOCKED"]
    E -->|否| KEEP["不修改当前活动<br/>避免旧任务误伤新代次"]
    PAUSE --> AUDIT["保存 CONSISTENCY_CONFLICT 审计<br/>记录订单、券和代码定义的冲突依据"]
    KEEP --> AUDIT
    AUDIT --> COMMIT["提交后释放锁<br/>输出一致性错误日志"]
    D -->|锁或数据库异常| FAIL["暂停事务失败<br/>原任务仍保留，后续继续核对"]
    AUDIT -->|写入或提交失败| FAIL
```

暂停不关闭请求、不释放库存，也不自动重开活动。
同代次且事实一致的已登记确认/释放仍可继续执行；要获得重建所需的静止快照，必须持有活动排他锁协调全部新链路写入口。

实现：[pauseConflict](../../../backend/src/main/java/com/hmdp/service/impl/SeckillMaintenanceServiceImpl.java#L429)、
[SeckillMaintenanceMapper.pause](../../../backend/src/main/java/com/hmdp/mapper/SeckillMaintenanceMapper.java)。

<a id="flow-11"></a>

## 11. 普通 MQ 消息消费与非法协议隔离

建单事务属于第三阶段沿用实现；本轮增加可靠隔离并区分协议错误与业务错误。

```mermaid
flowchart TD
    A["收到普通 Topic 消息批次"] --> B{"线程已中断？"}
    B -->|是| RETRY["返回 RECONSUME_LATER"]
    B -->|否| C{"标签、正文大小、JSON<br/>版本及必填字段合法？"}
    C -->|否| ISOLATE["独立事务保存 INVALID 隔离记录<br/>原文有界，不输出到日志"]
    ISOLATE -->|提交失败| RETRY
    ISOLATE -->|已提交| NEXT["当前消息处理完成<br/>继续检查批次中下一条"]
    C -->|是| CREATE["调用原幂等建单服务<br/>业务事务返回前已提交"]
    CREATE -->|成功| NEXT
    CREATE -->|业务身份、锁或数据库异常| RETRY
    NEXT --> MORE{"批次还有消息？"}
    MORE -->|是| B
    MORE -->|否| ACK["返回 CONSUME_SUCCESS"]
    RETRY -.-> BROKER["Broker 按重试策略继续投递<br/>耗尽后进入对应消费组 DLQ"]
    BROKER -.-> DLQ["流程 12：死信持久化隔离"]
```

合法 JSON 的业务身份不匹配仍属业务失败，不能按解析失败隔离后直接确认。
隔离提交成功后 Broker 再次投递同一非法消息也可幂等保存；批次中其他消息失败时，已提交结果由各自幂等保护。

实现：[RocketMqOrderListener.consumeMessage](../../../backend/src/main/java/com/hmdp/infrastructure/mq/RocketMqOrderListener.java#L42)、
[OrderMessageFailureStore.record](../../../backend/src/main/java/com/hmdp/infrastructure/mq/OrderMessageFailureStore.java#L40)。

<a id="flow-12"></a>

## 12. 死信接收与持久化隔离

入口：独立观察组订阅 `%DLQ%<实际订单消费组>`，前提是 Broker 和 ACL 已允许读取。

```mermaid
flowchart TD
    A["死信观察消费者收到消息"] --> B{"无外层事务且线程未中断？"}
    B -->|否| RETRY["返回 RECONSUME_LATER<br/>不丢弃死信"]
    B -->|是| C["计算正文 SHA-256<br/>构造稳定隔离编号"]
    C --> D{"正文不超过 64 KiB？"}
    D -->|是| BODY["保存有界原文及摘要"]
    D -->|否| HASH["不保存超大原文<br/>只保存摘要及消息元信息"]
    BODY --> T["独立数据库事务<br/>幂等插入 DLQ 隔离记录"]
    HASH --> T
    T -->|失败| RETRY
    T -->|提交完成| LOG["输出隔离告警<br/>不输出正文或凭据"]
    LOG --> MORE{"本批死信均已持久化？"}
    MORE -->|否| B
    MORE -->|是| ACK["返回 CONSUME_SUCCESS"]
    ACK --> KEEP["请求、订单和库存不因此改变<br/>等待核对重投或人工处置"]
```

INVALID 与 DLQ 共用同一持久化组件。来源、Topic、物理消息编号及正文摘要共同构成隔离编号的依据；重复接收不覆盖既有处置。
死信观察器本身的隔离失败持续交 Broker 重试，不采用普通订单组的有限重试上限。

实现：[RocketMqDeadLetterListener](../../../backend/src/main/java/com/hmdp/infrastructure/mq/RocketMqDeadLetterListener.java)、
[OrderMessageFailureStore](../../../backend/src/main/java/com/hmdp/infrastructure/mq/OrderMessageFailureStore.java)、
[RocketMqConfiguration](../../../backend/src/main/java/com/hmdp/config/RocketMqConfiguration.java)。

<a id="flow-13"></a>

## 13. 运维接口鉴权与分流

以下为 `/ops/seckill` 的保护流程。普通发券及原券初始化接口不属于此独立运维令牌入口。

```mermaid
flowchart TD
    A["应用启动"] --> B{"可靠组件和 operations-enabled<br/>同时开启？"}
    B -->|否| OFF["不注册运维控制器"]
    B -->|是| C{"独立运维令牌至少 32 字符<br/>且配置校验通过？"}
    C -->|否| BOOT_FAIL["拒绝启动该应用上下文"]
    C -->|是| D["注册运维路由，等待请求"]
    D --> E["已有登录拦截及控制器校验"]
    E --> F{"当前用户存在，且令牌<br/>通过常量时间字节比较？"}
    F -->|否| DENY["拒绝访问<br/>控制器返回 403，拦截器可提前返回 401"]
    F -->|是| G{"请求的操作？"}
    G -->|健康或隔离摘要查询| READ["返回数字指标或有界摘要<br/>不返回消息正文和凭据"]
    G -->|关闭请求| CLOSE["流程 06"]
    G -->|人工重投| REPLAY["流程 14"]
    G -->|处置隔离| DISMISS["流程 15"]
```

写操作的操作者编号来自当前登录用户，不能由正文覆盖。
HTTP 200 本身不代表业务成功，业务异常沿用现有 Result 失败约定。

实现：[SeckillMaintenanceController](../../../backend/src/main/java/com/hmdp/controller/SeckillMaintenanceController.java)、
[SeckillMaintenanceProperties](../../../backend/src/main/java/com/hmdp/config/SeckillMaintenanceProperties.java)。

<a id="flow-14"></a>

## 14. 人工受控重投原事件

入口：`POST /ops/seckill/orders/{orderId}/replay`。与流程 09 共享事实核对，增加人工租约规则和可选隔离处置。

```mermaid
flowchart TD
    A["通过流程 13 鉴权<br/>原订单号、原因、可选隔离编号"] --> B["校验参数，读取账本定位活动"]
    B --> C["独立事务：活动 S 锁 → 请求 X 锁<br/>核对 ACCEPTED、READY、代次及原订单"]
    C --> D["只读核对有效预占<br/>构造账本原消息，锁原 Outbox"]
    D --> E{"原事件存在？"}
    E -->|否| NEW["从原账本补建相同事件"]
    E -->|是| CHECK{"类型及消息体与账本一致？"}
    CHECK -->|否| PAUSE["先回滚，再转流程 10"]
    CHECK -->|是| LEASE{"存在尚有效的发送租约？"}
    LEASE -->|是| RB["回滚本次重投<br/>不抢占有效租约"]
    LEASE -->|否| RESET["重置原事件为 PENDING<br/>保留原 payload、编号和尝试次数"]
    NEW --> Q{"附带隔离编号？"}
    RESET --> Q
    Q -->|否| AUDIT["写操作者及 REPLAY 原因审计"]
    Q -->|是| VERIFY{"隔离未处置、有原文<br/>且原文消息与账本完全相同？"}
    VERIFY -->|否| RB
    VERIFY -->|是| RESOLVE["标记该隔离待办已重投处置"]
    RESOLVE --> AUDIT
    AUDIT --> COMMIT["提交后释放锁<br/>原投递器继续发送同一事件"]
    C -->|已知一致性冲突| PAUSE
    D -->|已知一致性冲突| PAUSE
    D -->|网络或锁异常| RB
    AUDIT -->|写入失败| RB
```

图中事件插入／重置先于隔离原文检查，但它们处于同一数据库事务；隔离检查失败会把先前重投修改一起回滚。
PENDING、SENT 或租约已过期的 SENDING 可以按条件唤醒；尚有效的 SENDING 拒绝重投。
隔离原文与账本不一致的校验失败不会自动替换 payload，也不等同于自动暂停活动。

实现：[replay](../../../backend/src/main/java/com/hmdp/service/impl/SeckillMaintenanceServiceImpl.java#L255)、
[replayLocked](../../../backend/src/main/java/com/hmdp/service/impl/SeckillMaintenanceServiceImpl.java#L279)、
[replayEvent](../../../backend/src/main/java/com/hmdp/mapper/SeckillMaintenanceMapper.java)。

<a id="flow-15"></a>

## 15. 人工处置无需重投的隔离消息

入口：`POST /ops/seckill/quarantine/{id}/dismiss`。这是关闭隔离待办，不是关闭业务请求。

```mermaid
flowchart TD
    A["通过流程 13 鉴权<br/>提交隔离编号及处置依据"] --> B["校验编号格式、操作者和原因"]
    B --> C["开始独立事务<br/>锁定原隔离记录"]
    C --> D{"记录存在且尚未处置？"}
    D -->|否| RB["回滚并返回失败<br/>不删除或覆盖已有记录"]
    D -->|是| E["写 resolved=1 和处置说明"]
    E --> F["同事务写 DISMISS_QUARANTINE 审计<br/>保留隔离编号和可信操作者"]
    F --> COMMIT["提交并释放记录锁"]
    COMMIT --> DONE["待办不再出现在未处置列表<br/>原文、摘要和消息元信息继续保留"]
    DONE --> KEEP["不改变请求、订单或库存"]
    C -->|锁或数据库异常| RB
    F -->|审计或提交失败| RB
```

重复处置返回失败，不新增库存动作。恢复扫描仍按账本处理长期请求；处置隔离记录不会阻止该请求按其他恢复路径继续推进。

实现：[dismissQuarantine](../../../backend/src/main/java/com/hmdp/service/impl/SeckillMaintenanceServiceImpl.java#L382)。

<a id="flow-16"></a>

## 16. 周期健康检查与日志告警

```mermaid
flowchart TD
    A["监控周期到达<br/>默认每 30 秒"] --> B["查询数据库健康快照"]
    B --> C["统计各类待办、最老年龄、最大尝试次数<br/>过期租约、未终态请求、隔离及恢复错误"]
    C --> D{"任一告警条件满足？"}
    D -->|是| ALERT["输出 WARN：SECKILL_BACKLOG_ALERT<br/>附数字指标，不附消息正文或令牌"]
    D -->|否| NORMAL["输出 DEBUG：SECKILL_HEALTH"]
    ALERT --> NEXT["本轮结束，等待下次调度"]
    NORMAL --> NEXT
    B -->|查询或统计异常| ERROR["输出 ERROR：SECKILL_MONITOR_FAILURE<br/>下一轮继续执行"]
    ERROR --> NEXT
```

触发条件：最老任务、请求或待发布活动年龄达到阈值；最大任务尝试次数达到阈值；存在 BLOCKED 活动、未处置隔离或活跃资源恢复错误。
默认年龄阈值 120 秒、尝试次数 10。指标中的 `expired_leases` 用于排查，但代码没有把“存在过期租约”单独设为即时告警条件。
这里是数据库业务待办与应用日志监控，不是 Broker 精确队列积压指标，也没有自动发送邮件或短信。

实现：[SeckillMaintenanceJob.monitor](../../../backend/src/main/java/com/hmdp/infrastructure/seckill/SeckillMaintenanceJob.java#L65)、
[SeckillMaintenanceMapper.health](../../../backend/src/main/java/com/hmdp/mapper/SeckillMaintenanceMapper.java)。

## 建议阅读顺序

先看 **01 → 02** 理解活动发布，再看 **03 → 04 → 05 → 06** 理解预占和关闭，
接着看 **07 → 08 → 09 → 10** 理解异常恢复，最后看 **11 → 12 → 13 → 14 → 15 → 16** 理解消息处置与运维。

源码是流程含义的依据。图中把同一事务的多条校验合并为一个节点时，细节以对应实现及 [第四阶段完整说明](ROCKETMQ_PHASE4.md) 为准。
本次没有运行或变更数据库迁移，没有重跑中间件测试，也没有修改第五阶段范围。
