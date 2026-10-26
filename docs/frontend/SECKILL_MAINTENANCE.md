# 秒杀维护页：本地测试使用

## 1. 解决的问题与范围

第四阶段已有后台补偿、恢复、隔离与人工运维接口，原前端没有对应入口，
测试人员需要手工拼接 HTTP 请求。本次新增 `/local/seckill`，在现有商城中
查看数据库积压、隔离消息摘要，并填写原订单号执行受控重投或关闭。

例如，抢购结果长期停在 ACCEPTED 时，先查看积压和隔离摘要，再从抢购结果或
数据库核对原订单号及预占，填写原因后提交重投。页面不会从物理消息编号猜订单号，
也不会自行生成订单号、修改消息正文或直接增加库存。

本次只补第四阶段的前端测试入口，不修改 Java、Lua、数据库或后台调度，不进入后续优化阶段。
自动发布、预占确认/释放、自动恢复和审计事务均沿用后端已有实现。
不新增用户取消订单、支付、退款、后台任务手动执行、完整订单列表或审计查询功能。

## 2. 本地启动

先按 [第四阶段说明](../optimization/order/ROCKETMQ_PHASE4.md) 完成数据库迁移，
并确认本地后端及相关依赖可用。

在启动后端的终端中设置以下环境变量，或在 IDE 的 Run Configuration 中设置同名变量，
然后按项目原有方式启动或重启后端：

```bash
# 执行目录：项目根目录。令牌自行设置，只在本地使用，至少 32 字符。
export SECKILL_RELIABLE_COMPONENTS_ENABLED=true
export SECKILL_MAINTENANCE_ENABLED=true
export SECKILL_OPERATIONS_ENABLED=true
export SECKILL_OPERATIONS_TOKEN='在这里替换为自行设置的至少32字符独立令牌'
```

前端可直接使用临时开关启动，无需修改已有 `.env.local`：

```bash
# 从项目根目录执行。依赖尚未安装时，先在 frontend/web 执行 npm ci。
cd frontend/web
VITE_SECKILL_OPS_ENABLED=true npm run dev
```

也可以在 `frontend/web/.env.local` 中设置 `VITE_SECKILL_OPS_ENABLED=true`，重启开发服务器。
`.env.example` 默认设为 false。代理仍沿用 `API_PROXY_TARGET`，默认指向
`http://127.0.0.1:8081`，浏览器经同源 `/api` 请求后端。

访问 [本地维护页面](http://127.0.0.1:5173/local/seckill?preview=0)，或点击主导航的“秒杀维护”。
切换实时数据、完成普通用户登录，再输入与后端一致的独立运维令牌，点击“连接 / 刷新”。
页面不自动轮询；需要观察后台进展时手动刷新。

只有 `import.meta.env.DEV` 和前端开关同时为 true 才注册页面和导航。
`npm run build` 的生产构建即使收到同名 true 开关也不注册页面，并移除维护页面代码分包。
前端开关只控制本地工具入口；实际权限仍由后端的登录校验和运维令牌校验决定。

令牌使用密码输入框，仅保留在当前页面 React 状态中，不存入浏览器存储、URL 或前端环境变量。
刷新、路由离开、切换预览或登录用户变化后需重新输入。可以点击“清除令牌”立即清空快照。

## 3. 页面功能与接口

| 区域 | 接口（路径相对于后端，不含前端 `/api` 代理前缀） | 行为 |
| --- | --- | --- |
| 运行概况 | `GET /ops/seckill/health` | 展示 16 项数据库指标；年龄单位为秒，未查询或失败显示 —，不伪装成 0 |
| 隔离消息 | `GET /ops/seckill/quarantine` | 展示最早未处置一批的来源、消息号、Topic、错误、重试次数、时间、隔离编号和正文摘要；不展示原文 |
| 重投原消息 | `POST /ops/seckill/orders/{orderId}/replay` | JSON 为 `reason` 和可选 `quarantineId`；后端核对原请求、预占、原事件与租约 |
| 关闭抢购请求 | `POST /ops/seckill/orders/{orderId}/close` | JSON 仅 `reason`；关闭未建单请求并登记释放任务，不宣称 Redis 已回补 |
| 标记已处置 | `POST /ops/seckill/quarantine/{quarantineId}/dismiss` | JSON 仅 `reason`；只标记隔离待办已处置，不改变订单、请求或库存 |

每个请求复用现有登录会话封装，发送原有 `authorization` 和额外的 `X-Seckill-Ops-Token`。
不在正文提供操作者编号，由后端从登录上下文取得并记录审计。

隔离列表不包含业务订单号。点击“关联重投”只填入隔离编号、清空上一次订单号，
并把焦点放到原订单号输入框，必须手动填写已核对的订单号。
关闭操作不会发送旁边的隔离编号，页面也明确注明该字段仅用于重投。

三个写操作均先展示确认弹窗，列出目标及操作后果，必须填写非空且不超过 256 字符的原因。
提交时禁用按钮，阻止重复提交和关闭弹窗；不自行重试网络超时或业务拒绝。
既有会话层仍沿用 401 时刷新登录并重试一次的协议。

## 4. 按文件说明与关键代码对比

所有路径相对于项目根目录。

| 文件 | 原有职责 | 本次改动及原因 |
| --- | --- | --- |
| `frontend/web/src/lib/local-tools.ts` | 原先无此文件 | 新增开发模式和显式开关判断，导航与路由共用同一条件 |
| `frontend/web/src/main.tsx` | 注册消费端路由及懒加载 | 新增条件路由和条件懒加载，生产构建不包含维护入口 |
| `frontend/web/src/components/layout.tsx` | 公共导航、主题、登录弹窗 | 开关开启时增加“秒杀维护”导航，继续复用登录和主题 |
| `frontend/web/src/styles.css` | 全站响应式样式 | 仅对启用本地工具的导航增加小屏横向滚动，防止新增入口撑宽页面 |
| `frontend/web/src/api/seckill-maintenance.ts` | 原先无此文件 | 新增接口类型、运维请求头及五个接口的接入；403/404/5xx 给出可理解的提示 |
| `frontend/web/src/pages/seckill-maintenance.tsx` | 原先无此文件 | 新增页面、只保存在内存的令牌、独立查询状态、输入校验和操作确认 |
| `frontend/web/src/seckill-maintenance.css` | 原先无此文件 | 新增作用域样式，复用品牌橙色、主题变量与既有 Radix 弹窗 |
| `frontend/web/.env.example` | 前端代理及业务配置示例 | 追加默认关闭的本地工具开关，不放入运维令牌 |
| `frontend/web/playwright.config.ts` | 普通商城测试配置 | 排除需要独立开关及端口的维护页测试 |
| `frontend/web/playwright.maintenance.config.ts` | 原先无此文件 | 新增独立测试配置，启动开启/关闭入口的开发服务及生产预览，不复用用户服务 |
| `frontend/web/tests/seckill-maintenance.spec.ts` | 原先无此文件 | 新增模拟接口的浏览器验证，所有写操作均被拦截，不执行真实业务修改 |
| 本文、`docs/frontend/README.md`、`docs/README.md` | 前端说明和文档入口 | 仅文档更新，提供启动、功能边界、关键代码与验证说明 |

原先：`main.tsx` 只注册商城、探店、个人中心等路由，没有维护页。
新增：

```tsx
// local-tools.ts：两个条件必须同时满足。
export const seckillOpsEnabled = import.meta.env.DEV && import.meta.env.VITE_SECKILL_OPS_ENABLED === 'true';
// main.tsx：条件同时约束代码加载和路由注册。
const SeckillMaintenance = seckillOpsEnabled ? lazy(() => import('./pages/seckill-maintenance')) : null;
{SeckillMaintenance && <Route path="local/seckill" element={<SeckillMaintenance />} />}
```

将条件放在 `lazy` 外层，使生产构建连维护分包也能移除，而不只隐藏导航。

原先无对应前端写操作；本次新增的关键行为：

```ts
// orderId 保持字符串，避免 18 位 Snowflake 编号经过 Number 后被舍入。
`/orders/${encodeURIComponent(action.orderId)}/${action.kind}`
// 只在重投且选择了隔离记录时发送关联编号；关闭和处置只发送原因。
const body = action.kind === 'replay' && action.quarantineId
  ? { reason, quarantineId: action.quarantineId }
  : { reason };
```

页面先用十进制格式和 `BigInt` 检查订单号在 Java 正 Long 范围内，
不会把编号转成普通浮点数字。提交函数使用同步 ref 防重复，不能只依赖下一次 React 渲染才禁用按钮。

## 5. 完整流程与实际分支

1. 页面先检查实时模式和登录状态。预览、未登录或登录恢复中不请求维护接口。
2. 输入令牌后显式查询健康及隔离接口；两者分别更新结果与成功查询时间，一项失败不遮住另一项结果。
3. 填写原订单号或选择隔离记录，核对弹窗目标和副作用，填写原因后提交一次。
4. 后端继续执行既有状态核对、锁和事务。页面不调整锁获取顺序、预占 Lua 或事务提交时机，
   不涉及商铺布隆过滤器与缓存互斥锁。
5. 重投成功只代表原事件重新可投递，关闭成功只代表关闭及释放任务提交；
   实际建单、Redis 释放和后续确认仍由既有消费者及后台任务推进。
6. 写接口成功后刷新健康与隔离摘要。刷新失败独立显示读取错误，不抹掉已经确认的写操作成功提示。

并发操作仍由后端裁决：例如页面查询后消费者先完成建单，随后点击关闭，
关闭必须被后端拒绝，页面保留原因并展示业务错误；不能依据旧快照直接回补库存。
遇到有效发送租约时，重投按已有后端规则拒绝，不由页面强行抢占。

网络超时无法确定写操作是否提交，页面保留弹窗和原因，提示先核对最新状态，
不显示成功或自动重复处置。用户可关闭弹窗刷新后再决定。
后端业务拒绝、事务回滚或锁冲突按现有业务响应显示；没有业务响应时无法据此断言已回滚。
浏览器取消查询、修改令牌或离开页面会中止旧读取，迟到结果不会覆盖新凭据下的视图。
后台任务中断、组件未就绪和活动状态不允许等情况仍由后端已有协议决定，页面不绕过。

403 提示检查登录及运维令牌；404 提示检查后端开关及代理；5xx 提示检查服务、迁移及依赖。
401 沿用现有会话续签；会话确认失效时退出维护工作区并打开登录框，清除页面内令牌。

## 6. 验证与剩余边界

以下命令从项目根目录进入前端模块后执行：

```bash
cd frontend/web
VITE_SECKILL_OPS_ENABLED=true npm run build
npx playwright test --config=playwright.maintenance.config.ts
```

维护页测试使用 5175、5176 两个临时开发服务及 5177 生产预览，覆盖开关、生产隔离、登录/预览隔离、
读接口错误、长订单编号、确认与取消、防重复、原因、隔离关联、处置后刷新、
网络超时/业务拒绝、令牌不落盘及深浅主题的小屏布局和 axe 检查。
截图在 `frontend/web/test-results/maintenance/screenshots/seckill-maintenance-*.png`。

2026-10-25 验证结果：

- `VITE_SECKILL_OPS_ENABLED=true npm run build` 通过；生产产物无维护页面 JS/CSS 分包。
- 独立维护页浏览器测试 16 项通过，包含生产预览拒绝直接路由、401 清理工作区及深浅主题 axe 检查。
- `npx playwright test --config=playwright.seckill.config.ts` 的现有商城/抢购用例中，12 项直接通过；
  另外两项遇到 trace 临时文件丢失，使用独立产物目录重跑后通过，14 个用例均已验证。
  重跑命令在 `frontend/web/` 执行：
  `npx playwright test --config=playwright.seckill.config.ts --grep '三个页面在四种宽度|后端错误显示重试入口' --output=/tmp/hmdp-maintenance-regression-20261025`。
- `git diff --check` 和本文相对链接检查通过；桌面及小屏截图已人工查看。

这些是模拟 HTTP 响应的浏览器契约测试，不是 MySQL、Redis、RocketMQ 的真实联合验收。
本次没有向真实后端重投、关闭或处置隔离消息；真实验证时须使用专门的测试券和请求，
按第四阶段文档检查最终账本、Outbox、预占状态及审计记录。
页面无法查看原始消息、完整订单列表或历史审计，也没有解除活动 BLOCKED 的操作。
