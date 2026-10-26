# 普通优惠券购买：直接数据库建单

日期：2026-10-26。本次补充普通券购买，使用已有券表和订单表，不增加库存表或消息流程。

## 1. 业务规则与原行为

原先普通券只展示价格、规则和“到店前请咨询商家”，不能购买。
现在上架普通券增加“立即购买”，确认后同步创建未支付订单，并显示完整字符串订单号。

- 只接受 `tb_voucher.type=0` 的普通券；秒杀券继续使用原可靠抢购入口。
- 普通券没有独立库存，每次购买一张，不读写秒杀库存，也不创建请求账本或 Outbox。
- 同一用户同一券只保留一张订单。重复提交返回已有订单号，不重置已有订单状态。
- 已有订单的用户在券下架后仍可重试找回原号；没有订单的用户不能购买未上架的券。
- 当前只创建订单，不实现扣款、支付、退款、核销或取消。已有取消/退款订单也不会被此入口重新创建。

## 2. 接口与实际顺序

`POST /voucher/{voucherId}/purchase`，需要现有登录身份，不需要请求正文。
用户编号由后端登录上下文取得，不相信正文里的 userId。
成功返回 `{"success":true,"data":"字符串订单号"}`；未登录为 401，非正数编号为 400，券不存在/类型不符/不可购买时 success=false。

服务在一个独立、读已提交的短事务中执行：

1. `SELECT ... FROM tb_voucher WHERE id=? FOR UPDATE`，读取并锁定当前券。
2. 券不存在或不是普通券时拒绝；秒杀库存和 Redis 预占不参与。
3. 按登录用户和券号查询已有订单，存在就返回原号，包括券已下架或原订单状态已变化的情况。
4. 没有原订单才检查券是否上架，再使用 MyBatis-Plus 本地 ID 生成器产生订单号，插入 `tb_voucher_order`。
5. 沿用数据库默认的未支付状态，提交事务并释放券锁后才返回成功；插单失败或异常则回滚。

例子：A、B 同时代表用户 7 购买券 501。A 先锁券、查重、插入 O90001 并提交；
B 等到券锁释放后才查询，读到 O90001 并返回同一编号，不再插单。
锁覆盖到提交，不能查重后就提前释放。不同用户可以买同一普通券，但也会短暂竞争同一个券行锁，
这是本次简单实现的取舍，不作为高并发秒杀方案。

数据库沿用已有 `(user_id,voucher_id)` 唯一索引作最终保护；本次没有新增数据库迁移。
普通购买不调用 Redis ID、预占 Lua 或 MQ；现有登录和整个应用的其他功能仍沿用各自依赖。

## 3. 文件职责及关键改动

| 文件，相对于项目根目录 | 改动与原因 |
|---|---|
| `backend/src/main/java/com/hmdp/service/IVoucherService.java` | 新增普通购买契约，说明类型、重复提交、事务和返回值 |
| `backend/src/main/java/com/hmdp/service/impl/VoucherServiceImpl.java` | 在现有券业务中组织锁券、查重和插单，复用已有短事务模板，不另建转调服务，不重新启用旧 Stream 订单服务 |
| `backend/src/main/java/com/hmdp/mapper/VoucherMapper.java` | 新增锁券查询，锁保持到调用方事务结束 |
| `backend/src/main/java/com/hmdp/controller/VoucherController.java` | 新增购买路由，从登录上下文取用户编号 |
| `frontend/web/src/api/vouchers.ts` | 新增普通购买请求，保留字符串订单号 |
| `frontend/web/src/pages/shop-detail.tsx` | 普通券展示购买按钮，下架/预览禁用；按类型选择普通购买或秒杀弹窗 |
| `frontend/web/src/components/ordinary-voucher-dialog.tsx` | 新增确认、提交防重复、订单号展示与复制，网络不确定时保留重试入口 |
| 后端单元/MVC、`OrdinaryVoucherPurchaseIT`、`storefront.spec.ts` | 新增类型/鉴权、原订单复用、回滚、并发及页面契约验证；既有发券测试仅适配新增依赖 |

原先没有普通购买方法；新增逻辑的关键顺序为：

```java
Voucher voucher = getBaseMapper().lockForPurchase(voucherId);
// 校验存在、type=0 后，先找回已有订单；没有订单时才检查上架并插入。
VoucherOrder existingOrder = voucherOrders.findUserVoucherOrder(userId, voucherId);
if (existingOrder != null) return Result.ok(existingOrder.getId().toString());
if (!Integer.valueOf(1).equals(voucher.getStatus())) return Result.fail("优惠券暂不可购买");
```

前端原先的普通券提示改为“立即购买”，增加单独的普通购买弹窗；原秒杀弹窗与请求协议继续沿用。
历史 Stream Controller/Service 仍不注册；旧页面参考代码保留。
商铺布隆、缓存清理和它们的锁顺序均没有改变，普通订单不新增缓存同步步骤。

## 4. 失败、重试和限制

网络超时不能说明订单没有提交，页面不直接展示成功；用户可重试同一用户、同一券的购买，数据库返回原订单。
业务明确拒绝直接显示原原因，不把下架或类型错误说成成功。
数据库/锁等待异常让本次事务失败，前端保留购买入口；没有 MQ 或后台任务替普通券自动重试。
入口拒绝已有外层事务与进入时已中断的线程，避免调用方误认为自己能撤销独立提交的购买；不自动清除中断标记。

价格和规则在确认页展示，后端从券资料判断资格；本轮没有支付金额结算、价格快照、订单详情列表或库存限购扩展。

## 5. 验证结果和执行方式

2026-10-26：183 项后端单元、MVC 和配置测试通过；前端构建及 16 项浏览器回归通过，
其中新增两项验证普通购买和网络结果未知后的重试。浏览器响应是模拟接口，不是真实数据库购买联调。

真实 MySQL 集成用例覆盖：创建未支付订单、不同用户购买、下架后找回原号、错误券类型、
同用户 16 次并发只建一单，以及实际插入后抛异常的事务回滚。
本轮尝试执行时，本地 MySQL 连接被拒绝且 Docker 引擎未启动，所以这 5 项尚未取得真实数据库通过结果。

在项目根目录执行：

```bash
mvn -f backend/pom.xml -Dtest='*Test' test
# MySQL 启动后执行；随机测试库用完后回收，不连接 Redis 或 MQ。
ORDINARY_VOUCHER_IT=true mvn -f backend/pom.xml -Dtest=OrdinaryVoucherPurchaseIT test
```

前端在 `frontend/web/` 执行：

```bash
npm run build
npx playwright test --config=playwright.seckill.config.ts
```

重启后端、刷新当前 React 页面即可看到普通券购买入口；没有运行数据库迁移或部署当前应用。
