# 黑马点评：本地开发依赖

通过 Docker Compose 统一管理 MySQL 和 Redis，Java 后端、JDK 和前端继续在本地运行。
使用官方镜像，无需编写 Dockerfile。

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
