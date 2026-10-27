# 用户抢购完整流程图与失败讲解

本页依据当前项目的可靠秒杀链路编写，覆盖用户点击、持久化受理、Redis 预占、Outbox 投递、RocketMQ 消费、数据库建单、结果查询及失败恢复。建单成功后仍未支付；支付、核销、退款不在本次流程范围内。

[打开图解导航](index.html) · [返回项目文档索引](../../README.md)

## 四张图的阅读顺序

| 图 | 打开 | 重点 |
|---|---|---|
| ① 用户提交与可靠受理（流程图） | [查看流程](acceptance/acceptance.html) | 登录、固定请求编号、两个短事务、预占返回码与明确拒绝 |
| ② 异步建单、失败与库存收尾（流程图） | [查看流程](async-order/async-order.html) | 投递租约、发送失败、消费重试、独立关闭事务与确认／释放 |
| ③ 成功抢购：事务与异步时序（时序图） | [查看时序](success-sequence/success-sequence.html) | 谁访问 MySQL、Redis、MQ；何时提交与确认消息 |
| ④ Redis 超时后保留原请求（时序图） | [查看时序](timeout-recovery/timeout-recovery.html) | 已扣库存却没收到响应时，如何沿原请求安全恢复 |

图中节点附有源码路径和行号。HTML 可独立打开，支持主题切换及查看器提供的导出功能。流程主线向下阅读，旁边为条件分支；为了避免大图难读，图①末尾衔接图②。

## 先把三种“成功”和两份库存分清

- `ACCEPTED`：Redis 已预占，数据库的受理状态和建单事件已一起提交；订单未必存在。
- MQ `SEND_OK`：发送调用收到 Broker 的成功回执；消费者未必已经建单。
- `SUCCEEDED`：数据库订单已经创建，订单默认仍未支付。

Redis 的 `stock` 是可供继续抢购的库存，预占时先减一；MySQL 的 `tb_seckill_voucher.stock` 在真正建单的事务内减一。它们在异步处理中可以暂时不同，不是把同一个库存错误地扣了两次。后续确认 Redis 预占只更新状态，不再扣库存；关闭请求后释放之前的预占，才可能把 Redis 库存加回一份。

例如初始库存为 1：甲预占后 Redis 为 0、数据库仍为 1；甲的消息建单提交后数据库为 0；后台确认后 Redis 仍为 0。若数据库明确无法建单，原建单事务回滚，再关闭请求，后台首次释放后 Redis 恢复为 1。

## 用户从点击到看到结果

1. 商铺页判断券是否可抢购，未登录则打开登录弹窗。前端时间和展示库存只用于交互，最终购买资格由后端判断。存在本次抢购记录时，即使页面显示售罄或已结束，仍可查看原请求进度。
2. 弹窗先生成 `requestId`，按“用户＋券”写入 `sessionStorage`，再发送请求。首次保存失败时不发送请求。刷新或重新打开弹窗会恢复原编号；新标签页、会话结束或手动清理存储不在这项保存保证内。
3. 提交 `POST /voucher-order/reliable/seckill/{voucherId}`，请求体为 `{ "requestId": "原编号" }`。用户编号来自登录上下文；接口检查身份、接单开关和参数。
4. 服务查询原账本。首次请求在**事务①**中登记 `PREPARING` 并独立提交，绑定固定 `orderId`、业务摘要和 `traceId`。同一用户的同一 `requestId` 不覆盖原记录；若换了券等业务参数，拒绝复用。
5. 若账本已经不是 `PREPARING`，直接返回原结果。否则进入**事务②**：先取活动共享锁，再取请求排他锁，重新读状态，检查活动为 `READY`。
6. 执行 `reserve-order.lua`。先核对 Redis 类型、元数据、代次和原预占；同订单已有有效 `RESERVED` 时直接返回原成功结果。首次预占则按 Redis 服务端时间检查活动，再检查用户归属和库存，原子扣一份并记录资格、预占。
7. 明确业务拒绝则在事务②中提交 `REJECTED`；预占成功则把 `ACCEPTED`、预占代次、受理时间和 `CREATE_ORDER` Outbox 一起提交。事务提交后才允许返回已受理。
8. Controller 返回查询到的**最新持久化状态**。后台可能已经建单，因此响应不保证一定停在 `ACCEPTED`。弹窗按原 `requestId` 查询，每轮最多 20 次，每次查询后约等待 1.5 秒；网络耗时会额外增加等待时长。达到轮次上限只提示继续确认，不关闭请求。
9. `SUCCEEDED` 显示“订单创建成功，尚未支付”；`REJECTED`、`CLOSED` 显示本次未成功，允许用户显式重新发起；`PREPARING`、`ACCEPTED` 继续等待、查询或重试原请求。

## 后台如何完成订单

Outbox 投递器在短事务中用 `FOR UPDATE SKIP LOCKED` 领取到期 `CREATE_ORDER`，提交 `SENDING`、租约令牌和到期时间。**网络发送发生在领取事务之外**，数据库锁不覆盖 MQ 网络等待。发送确认后，只有仍有效的租约才能记 `SENT`；失败登记下次重试时间，指数退避最高五分钟，不按次数直接丢弃事件。进程崩溃时由租约到期后的其他执行者接管。

消费者解析合法消息后，在**事务③**中按“活动共享锁 → 请求排他锁”核对订单号、用户号、券号、事件号、跟踪号、受理时间及代次。终态不再扣库；`SUCCEEDED` 仍要核对原订单真实存在且归属一致。`ACCEPTED` 已有匹配原订单时补成功状态和确认任务。尚无原订单时，检查同用户同券订单，再执行 `stock > 0` 的条件扣减并插单；数据库唯一索引兜住一人一单。扣库、插单、`SUCCEEDED` 和 `CONFIRM_RESERVATION` 一起提交，之后才 ACK。

若确认数据库无库存或存在同券订单冲突，**先回滚原建单事务，再开启独立关闭事务重新加锁查原订单**。两次事务之间可能有另一消费者建单成功：发现匹配原订单就走成功分支；仍无原订单才提交 `CLOSED` 和 `RELEASE_RESERVATION`。不是所有重复键都算业务冲突：回滚后没有查到同用户同券订单的重复键异常会继续抛出，交 Broker 重试。

确认／释放任务也保存在 Outbox 表，但由维护任务本地执行，不发送到建单 Topic。领取租约后，在新事务中依次锁活动、请求、任务，核对事件身份、代次和有效租约。确认要求 `SUCCEEDED` 且存在匹配原订单；释放要求 `CLOSED` 且原订单不存在。随后执行 Lua，登记任务 `SENT` 并提交；数据库锁持有至提交或回滚。

## 失败分支：用户看到什么，系统接着做什么

| 触发场景 | 持久化状态或影响 | 后续动作与库存含义 |
|---|---|---|
| 尚未登录／身份失效 | 页面先提示登录；接口身份失效为 401 | 登录后确认原请求；不能把身份失效认成业务拒绝 |
| 首次保存请求编号失败 | 不发送首次 POST | 修复浏览器存储后再试，尚无这次请求的预占 |
| 接单开关关闭 | POST 返回 503，不创建新账本 | 查询和后台继续；保留已有编号。关闭组件/API 注册开关不等于仅暂停提交 |
| JSON／路径／业务参数非法 | 400；不将已有账本覆盖成失败 | 修正参数；同编号不可改绑其他业务参数 |
| 首次账本登记或订单号生成异常 | 不保证已成功登记，响应可能失败 | 先查原编号，再以原编号重试；不可仅凭网络异常断言请求未执行 |
| 数据库锁冲突 | `NOWAIT` 冲突立即失败；可能保留 `PREPARING` | Controller 查询原账本，客户端或恢复任务沿原请求继续；事务超时也不等于自动重试 |
| 活动未 READY／Redis 未就绪／代次异常 | 不直接提交 `REJECTED` | 保留请求等待核对；尚未首次发布的活动由后台继续发布，冲突不得覆盖现有库存 |
| Lua 返回 4：未开始 | `REJECTED / NOT_STARTED` | 写零数量拒绝记录，本次不扣库存；以后重试同编号仍是原拒绝结果 |
| Lua 返回 5：已结束 | `REJECTED / ENDED` | 以 Redis 时间 `now >= endAt` 判结束，本次不扣库存 |
| Lua 返回 2：用户已有其他订单归属 | `REJECTED / DUPLICATE_USER` | 不删另一个订单的资格，不补库存 |
| Lua 返回 1：售罄 | `REJECTED / SOLD_OUT` | 本次没有成功预占，不加库存 |
| Lua 返回 3/6/7 或未知返回值 | 保留账本核对 | 未就绪、终态碰撞、标识冲突不能等价为“库存未扣” |
| Redis 超时／断网 | 事务②可能回滚，账本仍可为 `PREPARING` | Redis 命令可能已执行；同 orderId 重试可读到原预占，不盲目加库存 |
| Redis 已预占但事务②提交失败 | 原 `PREPARING` 仍在，Redis 可能为 `RESERVED` | 重试／恢复原请求后补受理与原 Outbox；MySQL 回滚不会回滚 Redis |
| HTTP 响应丢失／查询暂时 404 | 客户端结果待确认，后端状态未必失败 | 继续查询或重试原编号；404 不能证明迟到请求不会执行 |
| MQ 发送失败／超时／发送回执写库失败 | 保留建单事件；请求可仍是 `ACCEPTED` | 退避重发或租约接管；可能重复投递，不能直接释放预占 |
| 消费业务事务遇到技术异常 | 未确认消费，失败事务回滚 | Broker 重试；保持原订单身份，重复消息不重复扣库 |
| 非法协议、标签、超大／无法解析正文 | 先保存 `INVALID` 隔离记录 | 隔离提交成功后可确认；隔离保存失败则 Broker 重试，不自动关闭业务请求 |
| 消费重试耗尽进入死信 | 保存 `DLQ` 隔离记录 | 可靠隔离后确认；死信本身不改请求终态、不回补库存 |
| 数据库无库存／已确认同用户同券冲突 | 回滚建单，再重查；确认无原订单才 `CLOSED` | 关闭与释放任务同事务提交；随后异步归还原预占 |
| 确认／释放调用超时或任务回执回滚 | 终态可能已提交，任务仍未完成 | 后续重试 Lua；重复确认不扣库，重复释放不加库 |
| 线程中断 | 投递／维护停止继续领取，消费要求 Broker 重试；发送恢复中断标记 | 已领未确认任务由租约和后续幂等执行接管；中断不是自动成功或自动补偿 |
| 维护发现明确一致性冲突 | 保留记录并审计；有关路径按当前代次判断是否暂停活动 | 旧代次冲突不暂停新活动；修复或人工核对后决定后续操作 |
| 人工关闭未建单请求 | 活动 READY、身份与代次通过、无原订单且仍为 PREPARING/ACCEPTED 才能关闭 | 同事务写 CLOSED、释放任务和审计；关闭接口本身不直接加库存 |

前端目前会把提交异常统一提示为“结果待确认”，不会逐一显示所有 HTTP 错误的具体文案。上表区分的是后端事实和应继续采取的动作，不代表界面已经实现每一种专门提示。

## 两个必须按先后顺序理解的并发案例

### Redis 扣了库存，调用方却超时

1. 甲的事务①已提交 `PREPARING`，订单号 O 固定。
2. 事务②调用 Redis；Redis 实际把库存从 1 减到 0，保存 O 的 `RESERVED`，但响应丢失。
3. 事务②回滚，MySQL 仍为 `PREPARING`；Redis 不随之回滚。
4. 前端保留原编号；恢复任务或用户重试仍找到 O。
5. 再次预占核对用户、券、代次、归属均一致，直接返回原 `RESERVED`，库存保持 0；接着提交受理和建单事件。

如果第 3 步直接给库存加一，而原命令、消息或后续恢复又继续建单，其他用户就可能抢到同一份资格。因此超时分支不直接释放库存。详见图④。

### 人工关闭与迟到的预占命令

请求还在 `PREPARING`、原订单不存在时，运维可按限制显式关闭，并登记释放任务。若迟到预占先执行、随后释放看到 `RESERVED`，只归还之前暂扣的一份；若释放先看到预占缺失，仅对这类被显式关闭的原 PREPARING 写 `RELEASED` 零数量墓碑，**不凭空加库存**。迟到预占再执行会看到释放终态，不能再次扣库。已释放后用户用新订单购买，重复释放旧订单也不删除新订单的归属。

若另一个消费者已经先建成原订单，关闭事务在同一请求锁下重新查到订单后拒绝关闭；消费业务拒绝后的重新核对也会转为成功，避免“订单已经存在却又释放库存”。

## 三组状态不要混用

| 对象 | 状态 | 意义 |
|---|---|---|
| 请求账本 | PREPARING → ACCEPTED → SUCCEEDED | 登记 → 已持久化受理 → 数据库建单成功 |
| 请求拒绝／关闭 | PREPARING → REJECTED；PREPARING/ACCEPTED → CLOSED | 明确拒绝；或符合条件后关闭未建单请求。不是支付取消状态 |
| Redis 预占 | RESERVED → CONFIRMED / RELEASED | 之前暂扣一份 → 成功确认／释放；确认不再扣，首次释放才可能加一 |
| Redis 拒绝记录 | REJECTED，quantity=0 | 首次就未成功暂扣；后续不得按一份库存归还 |
| Outbox | PENDING → SENDING → SENT；失败回 PENDING，租约过期可接管 | 对建单事件 SENT 表示发送回执已记账；对本地确认／释放任务表示 Redis 操作已确认 |

长期 `PREPARING` 重新核对后调用原受理；长期 `ACCEPTED` 检查活动、代次、原订单、Redis 有效预占和原消息，再恢复原建单事件。自动恢复不抢正常 `PENDING/SENDING`；原事件已 SENT 或缺失时按核对结果恢复。**不会仅因等待太久自动关闭请求**。默认无进展 60 秒进入候选、恢复尝试间隔 60 秒、维护轮次间隔 5 秒，均可配置，也不保证到点立即完成。暂停、损坏数据或基础设施持续不可用时可能仍需人工核对。

## 源码职责与核对入口

路径均相对于项目根目录，本次仅增加图解文档，以下业务代码全部沿用。

| 文件 | 实际职责／重点位置 |
|---|---|
| `frontend/web/src/pages/shop-detail.tsx` | 选择优惠券、登录拦截、可抢购及查看原请求按钮，13–20、51–55、82 行 |
| `frontend/web/src/components/seckill-dialog.tsx` | 保存与恢复请求、轮询、原编号重试、结果显示，40–98、111–171 行 |
| `frontend/web/src/api/vouchers.ts` | 当前界面固定走可靠链路及提交／查询 API，19–20、37–46 行 |
| `backend/src/main/java/com/hmdp/controller/SeckillRequestController.java` | 可靠 HTTP 入口、开关、参数和用户归属查询，40–119 行 |
| `backend/src/main/java/com/hmdp/service/impl/SeckillRequestServiceImpl.java` | 独立账本事务、受理事务、Redis 预占与 Outbox，132–246 行 |
| `backend/src/main/resources/lua/seckill/reserve-order.lua` | 校验、时间、一人一份、原子预占和拒绝记录，9–88 行 |
| `backend/src/main/java/com/hmdp/infrastructure/mq/OrderOutboxDispatcher.java` | 租约领取、事务外发送、回执与退避，67–145 行 |
| `backend/src/main/java/com/hmdp/infrastructure/mq/RocketMqOrderListener.java` | 非法协议隔离、业务提交后 ACK、异常重投，42–80 行 |
| `backend/src/main/java/com/hmdp/service/impl/SeckillOrderServiceImpl.java` | 幂等建单、业务拒绝回滚后重查与关闭，63–228 行 |
| `backend/src/main/java/com/hmdp/service/impl/SeckillMaintenanceServiceImpl.java` | 确认释放、停滞恢复、核对重投、人工关闭、冲突审计，62–242、298–387、448–467 行 |
| `backend/src/main/resources/lua/seckill/finish-reservation.lua` | 确认不扣库、释放最多回补一份、零数量墓碑，31–65 行 |
| `backend/src/main/java/com/hmdp/mapper/SeckillCampaignControlMapper.java` | 活动共享／排他锁及 READY 发布，15–60 行 |
| `backend/src/main/java/com/hmdp/mapper/SeckillRequestMapper.java` | 请求幂等、NOWAIT 请求锁及状态更新，18–109 行 |
| `backend/src/main/java/com/hmdp/mapper/SeckillVoucherMapper.java` | 数据库 `stock > 0` 扣一份，16–23 行 |
| `backend/src/main/java/com/hmdp/mapper/MqOutboxMapper.java` | SKIP LOCKED 领取、租约有效性和回执条件，25–91 行 |
| `backend/src/main/java/com/hmdp/infrastructure/mq/RocketMqDeadLetterListener.java` | 先提交死信隔离再确认，不直接释放库存，25–42 行 |

仓库基线为 `3d27bf01b7f7a32b89ebed031f496df446ae6eb7`，源码引用按该提交核对。开始时 `docs/architecture/ROCKETMQ_ORDER_DESIGN.md` 已有未提交修改，本次没有修改它，也没有用其未提交内容冒充该提交的证据。旧 `VoucherOrderController`、`VoucherOrderServiceImpl` 及旧页面分支保留为历史参考，不作为当前运行路径。

## 验证与限制

[查看逐图交付与浏览器检查记录](verification.md)。本次验证针对 JSON 规范、源码引用、生成 HTML 和浏览器布局，没有重新执行真实 MySQL、Redis、RocketMQ 联调；流程语义依据当前代码，开关默认值不代表外部运行环境的实际配置。没有修改 Java、Lua、SQL、配置或测试代码，没有提交或推送。

如需重新生成，在**项目根目录**执行以下形式；`<目录>` 取上面四个目录之一，前两张类型为 `workflow`，后两张为 `sequence`。CLI 使用本次已安装技能的绝对路径，换机器后应替换为实际路径。已有浏览器证据时另给新 `--out-dir`：

```bash
node /Users/myl/.config/skillshare/skills/archify/bin/archify.mjs finalize <类型> \
  docs/architecture/seckill-purchase-20261026-162832/<目录>/candidate.json \
  docs/architecture/seckill-purchase-20261026-162832/<目录>/<目录>.html \
  --repo-root "$PWD" --quality showcase \
  --out-dir docs/architecture/seckill-purchase-20261026-162832/<目录>/recheck-1 --json
```
