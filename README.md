# 黑马点评：本地开发依赖

通过 Docker Compose 统一管理 MySQL 和 Redis，Java 后端、JDK 和前端继续在本地运行。
使用官方镜像，无需编写 Dockerfile。

## 项目目录

- `backend/`：Spring Boot 后端；常量按功能放在 `constants`，认证放在
  `security`，缓存、锁、Redis ID 和对象存储放在 `infrastructure`。
- `frontend/web/`：当前 React 前端，在此目录运行 `npm run dev`。
- `frontend/legacy/`：完整保留的旧 HTML 前端和资源。
- `frontend/nginx-1.18.0/`：保留的 Nginx 运行目录，`html/hmdp` 通过相对
  符号链接指向旧前端；不支持符号链接的环境可将 Nginx root 指向 legacy。
- `docker/`：本地依赖的辅助配置与脚本。
- `docs/`：按主题归类的说明、设计与阶段记录，见 [文档索引](docs/README.md)。

包职责、文件迁移对照和保留代码说明见 [项目结构说明](docs/architecture/STRUCTURE.md)。

## 当前秒杀入口：新链路

普通券已补充直接数据库购买：上架普通券点击“立即购买”，确认后创建未支付订单，重复购买返回已有编号。
接口及限制见 [普通券购买说明](docs/architecture/ORDINARY_VOUCHER_PURCHASE.md)。

理解整体业务先读 [RocketMQ 秒杀订单总设计](docs/architecture/ROCKETMQ_ORDER_DESIGN.md)：
按发券、受理、建单、收尾和恢复解释数据库、Redis、状态、流程时序及并发场景，无需按五个阶段逐份拼接。

当前 React 页面固定使用 `/voucher-order/reliable`，旧 Controller 和旧 Stream 消费者已停止注册，
旧代码与历史数据保留。新券通过 `POST /voucher/seckill` 自动准备 Redis 后开放购买。
API 实例启动会检查新管道开关完整性；`SECKILL_ACCEPTING_REQUESTS=false` 可暂停提交，原请求查询和后台收尾继续。
第四阶段及规则已提交为 `58ba8cdd`；本轮第五阶段实现、验证范围与后续故障演练见
[第五阶段说明](docs/optimization/order/ROCKETMQ_PHASE5.md)。以下阶段记录保留交付时的背景。

## 订单 RocketMQ 改造：第一阶段

本阶段接入原生 RocketMQ 5.3.2 客户端，提供订单事件的同步发送入口、
Spring 客户端生命周期管理和独立收发测试。第一阶段交付时秒杀业务仍使用原 Redis Stream，
尚未切换订单消费者；点赞、评论、关注和博客推送不在本次改造范围。
后续依次实现请求账本与 MySQL Outbox、幂等建单与结果查询、补偿对账及迁移。

已只读确认虚拟机 `10.211.55.9` 的 Broker 为 5.3.2，NameServer 为
`10.211.55.9:9876`，Broker 注册地址为 `10.211.55.9:10911`，ACL 未开启。
无需在本机 Compose 中重复部署 RocketMQ，也无需新增 Tomcat。

当前配置默认启用客户端，设置 `ROCKETMQ_ENABLED=false` 可关闭；启用时在后端启动阶段创建生产者。
此开关只控制客户端初始化，不改变秒杀通道，也不代表连接已经通过收发验证。
配置入口为 `hmdp.rocketmq`，地址通过 `ROCKETMQ_NAMESERVER` 覆盖。
SDK 默认不额外重试，未得到 `SEND_OK` 时向调用方报告失败或结果未知，
调用方不能据此回补库存。发送组件本身只负责同步发送，可靠重试交给第二阶段的 Outbox。

第二阶段已增加独立请求账本、按订单 Redis 预占、受理与待发消息同事务提交、
租约领取及退避投递。第二阶段交付时新组件默认关闭，后续消费者进度和当前联调配置见下文；
周期恢复和补偿尚未实施，不能仅打开开关就切换业务链路。
具体改动、事务顺序、Mermaid 流程和隔离测试见
[订单 RocketMQ 第二阶段说明](docs/optimization/order/ROCKETMQ_PHASE2.md)。
架构、数据库表职责、参考工程差异和复盘问题见
[第二阶段图解与复盘](docs/optimization/order/ROCKETMQ_PHASE2_REVIEW.md)。

第三阶段已实现独立订单消费者、数据库幂等建单、确认/释放任务登记、鉴权结果查询和前端进度展示，
第三阶段 YAML 为方便开发联调，默认开启可靠组件、独立 API、Outbox 和消费者；
当时前端需显式设置 `VITE_RELIABLE_SECKILL_ENABLED=true`，旧 HTTP 入口保留 Stream。
2026-10-25 已打通隔离环境的真实前后端与 MQ 建单链路，提交前完整后端回归 181 项全部通过；
确认/释放执行器和正式切换仍待后续阶段。详见
[订单 RocketMQ 第三阶段说明](docs/optimization/order/ROCKETMQ_PHASE3.md)。
从创建新券开始测试可按 [购买联调指南](docs/optimization/order/ROCKETMQ_PURCHASE_TEST.md) 操作，包含 SQL、Redis 初始化和 HTTP 请求。

第四阶段已补齐新券自动发布、预占确认/释放、后台恢复、非法消息与死信隔离、日志告警及受控重投，待联合验收。
启用前先执行第四阶段两份数据库迁移；运维接口默认关闭。当前实现、测试和验收步骤见
[第四阶段完整说明](docs/optimization/order/ROCKETMQ_PHASE4.md)。早期计划将旧 Stream 排空安排在第五阶段；
现按用户要求只启用新路径，不再实施旧链路维护，具体以第五阶段说明为准。

独立收发验证在项目根目录执行，不启动 Spring Boot、MySQL、Redis 或订单落库逻辑：

```bash
ROCKETMQ_SMOKE_TEST=true mvn -f backend/pom.xml -Dtest=RocketMqSmokeIT test
```

验证仅向 `hmdp-order-smoke` 发送一条合成事件，并使用独立临时消费组接收；
不会向业务订单 Topic 投递。首次运行需在 Broker 预建测试 Topic，
或由开发环境的 `autoCreateTopicEnable=true` 自动创建。
普通单元测试不会执行该测试，需同时指定测试类和环境开关。
若以后开启 ACL，通过 `ROCKETMQ_ACCESS_KEY`、`ROCKETMQ_SECRET_KEY` 配置，勿提交密钥。
客户端文件日志默认由 SDK 写入用户日志目录，可用 JVM 参数
`-Drocketmq.log.root=/absolute/path/to/logs` 指定位置。

阶段范围、配置、测试结果和后续任务见
[订单 RocketMQ 第一阶段说明](docs/optimization/order/ROCKETMQ_PHASE1.md)。

## 商铺详情缓存回源并发

商铺详情的互斥缓存查询在锁内二次检查缓存后，才申请数据库回源许可。
每个后端实例默认最多同时执行 10 次此类查询；许可用满时立即返回
“系统繁忙，请稍后再试”，不会排队等待许可或写入空值缓存。
正常缓存和空值缓存命中不占用许可；查库成功、无记录和抛出异常均会归还许可。

可通过环境变量 `CACHE_MAX_CONCURRENT_FALLBACKS` 或配置项
`hmdp.cache.max-concurrent-fallbacks` 调整，必须为正整数，修改后需重启后端。
上限需结合压测、数据库连接池和其他业务负载确定；当前连接池上限为 20。
这是实例级限制，多个实例的额度会累加，不覆盖分类、搜索、列表或其他直接查库入口。

## 商铺布隆过滤器

当前已完成快照构建、缓存未命中观察、新增商铺同步和可配置的负向拦截。
配置为 `hmdp.cache.shop-bloom`，默认启用，版本 `v1`、预期容量 100000、
误判率 0.001、每批 500 个 ID。可用 `SHOP_BLOOM_ENABLED=false` 关闭。
基础配置参数见 [布隆过滤器第一阶段说明](docs/optimization/cache/BLOOM_FILTER_PHASE1.md)。

同版本已就绪快照会复用；未就绪时由一个实例在启动阶段分批构建。
首次构建会增加启动时间，观察会增加缓存未命中请求的 Redis 访问。
`POST /shop` 已改用统一新增入口，在数据库提交前加入就绪快照，提交后清理详情缓存。
构建持全局生命周期写锁，新增持读锁；新增还持有与详情回源相同的商铺锁，直到提交及清理结束。
旧快照缺少 `syncProtocol=2` 时会重新构建，避免直接沿用可能漏增量的第一阶段数据。
第二阶段的事务边界、测试和剩余限制见 [新增同步说明](docs/optimization/cache/BLOOM_FILTER_PHASE2.md)。
第三阶段提供 `hmdp.cache.shop-bloom.enforce` / `SHOP_BLOOM_ENFORCE` 开关。
当前 `application.yaml` 的默认值为 `true`，可设置为 `false` 恢复观察模式，修改后重启生效。
开启后只拦截缓存未命中且就绪快照明确不包含的 ID，
不获取详情锁、不查库，也不为被拦截的随机 ID 写入空值缓存。
正常缓存和空值缓存命中直接返回；可能存在、未就绪或组件关闭时继续原查询流程。
观察异常仍沿用原有处理，不作为负向拦截依据；布隆误判由数据库和空值缓存兜底。
启用前必须确认全部新增入口同步；直接 SQL 导入前应关闭拦截，完成后重建完整快照。
`READY` 只说明快照已就绪，无法证明外部写入已经同步。
具体代码对比、入口审计与验证见 [第三阶段说明](docs/optimization/cache/BLOOM_FILTER_PHASE3.md)。

第四阶段增加本地 JMX 手动重建：当前 `SHOP_BLOOM_JMX_ENABLED` 默认 `true`，
可设置为 `false` 关闭导出。启用并启动后，可通过本地 JConsole 调用
`com.hmdp:type=ShopBloom,name=Maintenance` 的 `rebuild` 操作，反复重建当前版本，
无需每次重启；`LastBuildStatus` 属性查看最近一次实际构建记录。
重建复用生命周期写锁，完整成功后才发布；已有可用 READY 时保留旧快照服务查询，
失败只清理本次候选。构建记录存放在独立的 `hmdp:bloom:shop:build:state:<version>`，
不覆盖查询元数据。运行中重建会阻止新增取得生命周期读锁，新增等待超过 1 秒会返回繁忙。
外部数据导入应先关闭全部查询实例的负向拦截，再导入、重建并检查 SUCCESS，最后恢复拦截。
完整步骤与边界见 [第四阶段说明](docs/optimization/cache/BLOOM_FILTER_PHASE4.md)。

## 后端 JWT 配置

启动后端前，在 IDEA 的环境变量中配置 `HMDP_JWT_SECRET`，值可用以下命令生成：

```bash
openssl rand -base64 48
```

密钥以 UTF-8 文本读取，至少 32 字节，不做 Base64 解码；不要提交到 Git。
本地也可使用已忽略的 `application-dev.yaml` 中的 `dev.secret`，环境变量优先。
密钥缺失或过短时启动失败；更换密钥会使已有访问 JWT 失效，现有刷新会话仍可续签。
如需强制所有用户重新登录，还需撤销对应的 Redis 会话。
根目录 Compose `.env` 不会自动传给本地运行的 Java 程序。

登录返回值仍是访问 token 字符串；后端同时接受裸串和 `Bearer <token>`。
访问 JWT 默认有效期为 2 小时；刷新凭证通过 `hmdp_refresh` HttpOnly、SameSite=Lax
持久 Cookie 保存，七天有效期从登录时计算，刷新不会延长这七天。
Redis 使用 `hmdp:login:session:{sid}` Hash 保存用户 ID、资料快照、刷新凭证的
SHA-256 摘要和绝对到期时间，不保存刷新凭证明文。
鉴权本地验签后执行一次 HGET 核对用户 ID。每次签发使用新 jti、相同 sid；
退出删除当前 sid 会话，使该端全部访问令牌和刷新凭证失效，其他端不受影响。

React 前端在访问令牌临近过期时调用 `POST /user/refresh`，收到 401 时最多刷新并重试一次；
关闭页面后用持久 Cookie 恢复登录，无需把 token 写进 localStorage。
localStorage 只保存恢复提示，多标签页通过 Web Locks 串行轮换共享 Cookie，退出时同步清理。
刷新接口原子轮换凭证，拒绝旧凭证重放；原有 Redis TTL 不延长。
刷新和 Cookie 登出要求 `X-Requested-With: XMLHttpRequest`，避免跨站表单使用 Cookie。
HTTPS 部署设置 `JWT_REFRESH_COOKIE_SECURE=true`；本地 HTTP 开发保持默认 false。
前后端通过同源 `/api` 代理连接，访问时不要混用 localhost 和 127.0.0.1。
旧 Vue 前端保留登录返回字符串的兼容性，自动恢复与续期仅在 React 前端接入。
昵称、头像是签发时的资料快照，后续若增加资料编辑，需要补充资料刷新或会话失效机制。

旧 UUID 会话及不含 sid 的旧 JWT 不再接受，本次升级后需重新登录一次。
旧的 `hmdp:login:token:*` 键可等待自然过期，无需清空 Redis。
验证码仍仅输出到开发日志，尚未对接短信平台；有效期两分钟，60 秒内不能重复发送，
验证成功即原子消费，连续失败五次锁定十分钟。

## 完整 Docker 部署

在项目根目录运行 `./hmdp-plus`（Windows 使用 `.\hmdp-plus.cmd`），构建并启动前端、后端、MySQL 和 Redis。
首次启动自动初始化数据库；秒杀及图片上传需要额外配置。详细说明见
[Docker 部署指南](docs/frontend/DOCKER_DEPLOY.md)。

## 本地依赖一键启动

先启动 OrbStack 或 Docker Desktop，确保 `docker compose version` 可用。
在项目根目录执行：

```bash
docker compose up -d --wait
```

首次运行会下载 MySQL 8.4、Redis 7.4 镜像，并等待两个服务健康后返回。
MySQL 首次创建空数据卷时会自动创建 `hm-dp` 数据库，并导入
`backend/src/main/resources/db/hmdp.sql` 中的表和示例数据。
Redis 启用 AOF 持久化；健康检查只检测 PING，不再创建旧订单 Stream 或消费组。
已存在的旧 Stream、消费组和消息保留，不自动迁移或删除。
仅启动这两个容器，不额外启动初始化容器。

## 本地连接

| 服务 | 地址 | 用户名 | 开发密码 | 数据库 |
| --- | --- | --- | --- | --- |
| MySQL | `127.0.0.1:3306` | `root` | `123456` | `hm-dp` |
| Redis | `127.0.0.1:6379` | 默认用户 | `20041223` | `0` |

端口仅绑定本机回环地址。以上与后端 `application.yaml` 的默认值一致，
本地运行后端时无需更改连接配置；如 IDEA 已设置连接环境变量，请检查是否覆盖了这些默认值。

**已有的本地 MySQL、Redis 或其他容器若占用了 3306、6379 端口，需要先停止，或修改映射端口。**
旧实例的数据不会自动迁入；此配置创建独立数据卷，使用项目自带的示例 SQL 初始化。
Redis 原有缓存、登录状态、GEO 店铺坐标、秒杀库存等业务数据也不会自动复制。
需要时可按项目现有方式重建这些数据，例如运行后端测试中的 `loadShopData` 导入 GEO 坐标。

## 可选：修改端口或密码

```bash
cp .env.example .env
```

修改 `.env` 后再次执行启动命令。若保留原本地服务，可把端口改成
`MYSQL_PORT=3307`、`REDIS_PORT=6380`。

根目录 `.env` 只供 Compose 读取，**Spring Boot 不会自动加载该文件**。
修改后，还需在 IDEA 后端运行配置的“环境变量”中设置相同值，例如：

```text
MYSQL_PORT=3307;REDIS_PORT=6380
```

修改数据库名或密码时，同步设置后端的 `MYSQL_DATABASE`、`MYSQL_PASSWORD`、
`REDIS_PASSWORD`；MySQL 用户名保持 `root`。
MySQL 数据库名和密码的初始化变量仅在空数据卷首次初始化时生效，
已有数据库请通过 SQL 修改，随后同步 `.env` 和后端环境变量。

## 日常管理

```bash
# 查看状态
docker compose ps

# 实时查看日志（Ctrl+C 退出日志，不会停止容器）
docker compose logs -f

# 一起停止 / 恢复
docker compose stop
docker compose start --wait

# 删除容器和网络，保留数据卷；下次 up 会复用数据
docker compose down

# 打开 MySQL 客户端，输入上表密码或自定义密码
docker compose exec mysql mysql -uroot -p hm-dp

# 打开 Redis 客户端并交互输入密码
docker compose exec redis redis-cli --askpass
```

示例 MySQL 客户端命令使用默认数据库名；自定义后请替换 `hm-dp`。
修改初始化 SQL 不会让已有数据卷重复导入。

仅当确定要**清空这套 Compose 的全部 MySQL 和 Redis 数据**并重新导入示例数据时执行：

```bash
docker compose down -v
docker compose up -d --wait
```

参考：[Compose 服务配置](https://docs.docker.com/reference/compose-file/services/)、
[MySQL 官方镜像](https://hub.docker.com/_/mysql)、
[Redis 官方镜像](https://hub.docker.com/_/redis)。
