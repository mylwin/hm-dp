# 黑马点评：本地开发依赖

通过 Docker Compose 统一管理 MySQL 和 Redis，Java 后端、JDK 和前端继续在本地运行。
使用官方镜像，无需编写 Dockerfile。

## 后端 JWT 配置

启动后端前，在 IDEA 的环境变量中配置 `HMDP_JWT_SECRET`，值可用以下命令生成：

```bash
openssl rand -base64 48
```

密钥以 UTF-8 文本读取，至少 32 字节，不做 Base64 解码；不要提交到 Git。
本地也可使用已忽略的 `application-dev.yaml` 中的 `dev.secret`，环境变量优先。
密钥缺失或过短时启动失败；更换密钥会使已有 JWT 全部失效，需要重新登录。
根目录 Compose `.env` 不会自动传给本地运行的 Java 程序。

登录返回值仍是 token 字符串，前端存储与请求方式保持不变；后端同时接受裸串和
`Bearer <token>`。JWT 固定 12 小时过期，Redis 会话 TTL 对齐 `exp`，不滑动续期。
每次有效请求本地验签后读取一次 Redis 白名单；退出只删除当前 `jti` 的会话。
昵称、头像是签发时的资料快照，后续若增加资料编辑，需要补充资料刷新或会话失效机制。

旧 UUID 会话不再接受，前端收到 401 后需重新登录。
旧的 `hmdp:login:token:*` 键可等待自然过期，无需清空 Redis。
验证码仍仅输出到开发日志，尚未对接短信平台；有效期两分钟，60 秒内不能重复发送，
验证成功即原子消费，连续失败五次锁定十分钟。

## 一键启动

先启动 OrbStack 或 Docker Desktop，确保 `docker compose version` 可用。
在项目根目录执行：

```bash
docker compose up -d --wait
```

首次运行会下载 MySQL 8.4、Redis 7.4 镜像，并等待两个服务健康后返回。
MySQL 首次创建空数据卷时会自动创建 `hm-dp` 数据库，并导入
`backend/src/main/resources/db/hmdp.sql` 中的表和示例数据。
Redis 启用 AOF 持久化；健康检查会幂等创建后端需要的
`hmdp:stream:orders` Stream 和 `g1` 消费组，从 `0` 开始读取，已有消费进度不会被重置。
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
