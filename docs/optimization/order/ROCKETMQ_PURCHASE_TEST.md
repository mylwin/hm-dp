# 从创建优惠券到 MQ 建单的手动联调

本指南使用全新测试券，不复用缺少活动数据的券 30。验证范围是购买受理、MQ 投递与消费、数据库建单和结果查询；
订单成功后仍未支付，确认/释放任务执行器属于第四阶段。

## 1. 创建优惠券

在数据库客户端选择 `hm-dp` 开发库，完整执行 [create-test-voucher.sql](scripts/create-test-voucher.sql)。
脚本不带 USE，避免覆盖客户端选中的目标库；第二、三阶段迁移必须已完成，店铺 1 必须存在。
同一个 SQL 控制台执行全部语句，遇到错误停止并执行 ROLLBACK，不能跳过错误继续 COMMIT。

脚本创建三张表中的活动数据：

| 表 | 本次写入 |
|---|---|
| tb_voucher | 店铺 1，上架秒杀券；支付 5000 分，抵扣 10000 分 |
| tb_seckill_voucher | 库存 10；从数据库当前时间前一天到后七天 |
| tb_seckill_campaign_control | epoch=1，初始 PAUSED；Redis 准备好后才开放 |

每次执行都创建一张新券。记录第一个结果集中的 voucher_id，不能把示例编号或订单号代入购买接口。
确认 existing_orders=0；如果不是 0，该编号存在历史孤立订单，保留它并重新运行脚本创建下一张新券。
SQL 会将当前会话时区设为 UTC，结果中的时间按 UTC 显示，Redis 毫秒时间由同一组值计算。

也可以从项目根目录用 MySQL CLI 执行（结果宽度较长，建议使用数据库客户端复制生成的命令）：

```bash
mysql --default-character-set=utf8mb4 -h 127.0.0.1 -P 3306 -u root -p hm-dp \
  < docs/optimization/order/scripts/create-test-voucher.sql
```

## 2. 初始化 Redis，再开放活动

SQL 第二个结果集返回 `redis_init_command`。复制该字段的完整内容到 **同一后端使用的 Redis 实例和数据库**执行，
可用 IDEA Redis 控制台或其他 Redis 客户端；命令自身不包含连接密码。

该命令在一次 Lua 调用中检查库存、元数据和用户归属键均未存在，再初始化：

```text
hmdp:seckill:{实际券编号}:stock = "10"
hmdp:seckill:{实际券编号}:meta = Hash
  ready   = "1"
  epoch   = "1"
  beginAt = 数据库开始时间对应的 Unix 毫秒
  endAt   = 数据库结束时间对应的 Unix 毫秒
```

users 和 reservation 键由实际抢购创建。不要手工插入订单、请求账本或 Outbox。
初始化命令应返回 OK。若返回 already_initialized，说明至少一个键已经存在；不要删除键或覆盖库存，
先查看是否已初始化/抢购。需要独立新样本时重新执行 SQL 建券即可。

Redis 返回 OK 且 existing_orders=0 后，复制第三个 SQL 结果集 `enable_campaign_sql` 到 MySQL 执行。
首次应更新 1 行，把 PAUSED 改为 READY；再次执行更新 0 行时，查询是否已经 READY，不重置数据。
SQL 和 Redis 不在同一事务中，以上是手动开发夹具，不能作为生产活动发布或重建方案。

## 3. 确认运行开关

后端 IDEA 运行配置中确认以下值生效；当前本地 YAML 已开启部分开关，环境变量可以明确覆盖：

```text
ROCKETMQ_ENABLED=true
ROCKETMQ_NAMESERVER=10.211.55.9:9876
SECKILL_RELIABLE_COMPONENTS_ENABLED=true
SECKILL_RELIABLE_API_ENABLED=true
ORDER_OUTBOX_ENABLED=true
ORDER_CONSUMER_ENABLED=true
```

配置改变后重启后端。消费者启动会检查订单表 user_id、voucher_id 的业务唯一索引。
表和索引迁移使用现有脚本，不要为了本次测试重新导入会 DROP TABLE 的 hmdp.sql。

## 4. 从购买开始测试

IDEA HTTP Client 中创建请求。把 voucherId 换成 SQL 返回的实际编号，把 accessToken 换成当前登录令牌。
变量值和 Authorization 请求头均不加引号，JSON 中 requestId 必须加双引号。

```http
@baseUrl = http://localhost:8081
@voucherId = 替换为SQL返回的券编号
@accessToken = 替换为当前登录令牌
@requestId = debug-purchase-本次券编号-001

### 提交一次购买；重试必须保持 requestId 不变
POST {{baseUrl}}/voucher-order/reliable/seckill/{{voucherId}}
Content-Type: application/json
Authorization: Bearer {{accessToken}}

{
  "requestId": "{{requestId}}"
}

### 查询同一请求，直到返回 SUCCEEDED
GET {{baseUrl}}/voucher-order/reliable/requests/{{requestId}}
Authorization: Bearer {{accessToken}}
```

requestId 中“本次券编号”也要替换为实际数字，使编号只含字母、数字、下划线和中划线。
新券不能复用其他券已经使用的 requestId；同一券同一次购买的超时重试始终沿用原 requestId。
POST 可能返回 ACCEPTED，也可能因后台很快完成而直接返回 SUCCEEDED；以查询结果为准。

从前端测试时，在 `frontend/web/` 目录启动：

```bash
VITE_RELIABLE_SECKILL_ENABLED=true \
API_PROXY_TARGET=http://127.0.0.1:8081 npm run dev
```

登录后打开店铺 1，刷新券列表，购买“RocketMQ 下单联调券”。有多张同标题测试券时，
在 Network 的券列表响应中核对 id；购买请求应走 `/api/voucher-order/reliable/seckill/{id}`。
前端会自行生成 requestId，无需再手工提交同一用户同一券。

## 5. 核对结果和断点

在 MySQL 新开查询窗口时，先把下面的券编号变量换成真实值；user_id 换成当前登录用户编号。

```sql
SET @test_voucher_id = 替换为实际券编号;
SET @test_user_id = 1;

SELECT voucher_id, epoch, status
FROM tb_seckill_campaign_control WHERE voucher_id = @test_voucher_id;

SELECT voucher_id, stock FROM tb_seckill_voucher WHERE voucher_id = @test_voucher_id;

SELECT order_id, user_id, request_id, status, failure_reason
FROM tb_seckill_request
WHERE voucher_id = @test_voucher_id AND user_id = @test_user_id;

SELECT id, user_id, voucher_id, status
FROM tb_voucher_order
WHERE voucher_id = @test_voucher_id AND user_id = @test_user_id;

SELECT o.event_id, o.event_type, o.status, o.attempts, o.last_error
FROM tb_mq_outbox o JOIN tb_seckill_request r ON r.order_id = o.order_id
WHERE r.voucher_id = @test_voucher_id AND r.user_id = @test_user_id;
```

仅有本次一位用户抢购时，最终预期：

| 检查对象 | 预期结果 |
|---|---|
| 请求 | SUCCEEDED |
| tb_voucher_order | 当前用户一张订单，status=1，尚未支付 |
| 数据库库存 | 10 → 9 |
| Redis stock | 10 → 9 |
| CREATE_ORDER | 最终 SENT |
| CONFIRM_RESERVATION | PENDING，第三阶段的正常边界 |
| reservation | RESERVED，等待第四阶段确认执行器 |

流程是：购买 POST → T1 保存 PREPARING → T2 锁活动和请求 → Redis Lua 预占 →
T2 提交 ACCEPTED 与 CREATE_ORDER → Outbox 投递 RocketMQ → 消费者事务扣库、建单、
更新 SUCCEEDED 并登记确认任务 → 提交后确认消费 → 页面/HTTP 查询结果。

建议依次在 Controller 调用 accept 前、acceptLocked 的 reserve 前后、RocketMqOrderListener 调用 createFromMessage 前、
SeckillOrderServiceImpl 条件扣库处打断点。事务内暂停超过默认 5 秒可能导致超时，
适合慢看的入口放在事务外；更详细的调试说明见 [第三阶段第 8.5 节](ROCKETMQ_PHASE3.md#85-手动打断点查看功能)。

先重复原 POST 验证订单仍一张、库存仍 9；然后改一个新的 requestId 验证同用户同券被拒绝，
返回 REJECTED / DUPLICATE_USER。要再次验证成功，创建新券或换另一登录用户，不清除用户归属键绕过一人一单。

## 6. 脚本验证记录

2026-10-25 在随机临时 MySQL 库中，按项目实际表结构连续执行建券脚本两次：
生成两张不同编号的券，两张秒杀库存记录均为 10，初始控制状态均为 PAUSED，没有预建订单。
逐条执行脚本输出的 Redis 初始化 Lua，两次均返回 OK，ready、epoch 和毫秒时间窗口正确。
测试将各自库存改为 9 后再次执行初始化，均返回 already_initialized，库存仍为 9。
随后执行各自的 enable_campaign_sql，两条活动均变为 READY。

验证使用真实 MySQL 和 Redis，Redis 键替换为随机验证前缀，避免触碰相同数字编号的业务键。
临时库和测试键已清理；没有把新券实际写入用户开发库，没有替用户执行购买。
本次是脚本验证，完整 HTTP/MQ 建单结果沿用 [第三阶段联调记录](ROCKETMQ_PHASE3.md#84-2026-10-25-真实前后端联调)，
不将前阶段测试计为本次重新执行。脚本验证报告位于项目根目录下 `backend/target/purchase-fixture-validation-20261025.json`。
