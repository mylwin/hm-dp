# hmdp-plus Docker 部署

在项目根目录执行 `./hmdp-plus`，首次构建需要 Docker Compose、openssl 和镜像及依赖下载网络。脚本首次创建根目录 `.env`，生成 MySQL、Redis 和 JWT 的独立随机密钥；该文件已被 Git 忽略，应保留并备份。已有 `.env` 不会被覆盖：必须自行补齐 `MYSQL_PASSWORD`、`REDIS_PASSWORD` 和至少 32 字节的 `HMDP_JWT_SECRET`。根目录的原本地依赖 Compose 也读取该文件，修改共享变量会影响它的配置。

脚本等待四个容器的健康检查，成功后显示前端地址和通过前端代理访问的后端 API 地址；默认分别为 `http://127.0.0.1:8080/` 和 `http://127.0.0.1:8080/api/`。失败时显示 Compose 错误、容器状态和最近日志，详细日志可用 `./hmdp-plus logs` 查看。设置 `HMDP_HTTP_PORT` 改端口，`HMDP_BIND_ADDRESS=0.0.0.0` 允许外部访问；对外发布应通过 HTTPS 反向代理，并设置 `JWT_REFRESH_COOKIE_SECURE=true`。后端、MySQL 和 Redis 不直接向宿主机暴露端口。容器独立于原本地依赖 Compose，使用独立数据卷。

后端镜像构建默认通过阿里云 Maven 公共镜像下载中央仓库依赖，并在 Docker 构建缓存中保留已下载的依赖。可在 `.env` 中设置 `MAVEN_MIRROR_URL` 为可信的其他 Maven 仓库；如需直接使用 Maven Central，设置为 `https://repo.maven.apache.org/maven2`。修改后重新运行 `./hmdp-plus`。镜像仍需从 Docker 镜像仓库下载，Maven 镜像配置不能替代 Docker Registry 加速。

第一次创建空数据库卷时按顺序导入 `hmdp.sql` 和四份订单迁移。已有卷不会重新导入或自动升级，升级前请备份并人工审查迁移；导入失败时检查 `./hmdp-plus logs mysql`，初始化中途失败也可能留下已创建的数据卷，不能直接假定重启会继续导入。不要对已有业务数据库重复导入基础 SQL，其中包含删表语句。

基础部署默认关闭秒杀链路。要启用秒杀，在 `.env` 设置 `HMDP_SECKILL_ENABLED=true` 和从容器可访问的 `ROCKETMQ_NAMESERVER`，并确保 Broker 广播地址也可达、Topic 和消费组已配置；开启 ACL 时设置 `ROCKETMQ_ACCESS_KEY` 和 `ROCKETMQ_SECRET_KEY`。修改配置后重新运行 `./hmdp-plus`。原有券的数据和 Redis 库存不会自动迁移，新建秒杀券才会按现有流程准备库存。服务健康不代表 RocketMQ 收发验证通过。

图片上传需要在 `.env` 配置 `OSS_ACCESS_KEY_ID`、`OSS_ACCESS_KEY_SECRET`、`OSS_BUCKET_NAME`；缺省值仅允许服务初始化，不能上传。OSS 地域沿用后端现有配置。商铺距离查询所需的 Redis GEO 数据不会从 SQL 自动导入，需按项目现有方法初始化。验证码输出在后端日志中，尚未对接短信。

## 部署后操作

以下命令均在项目根目录执行。

1. 查看服务状态：

   ```bash
   ./hmdp-plus ps
   ```

2. 本地登录时，在另一个终端持续查看后端日志，再在页面点击发送验证码：

   ```bash
   ./hmdp-plus logs -f backend
   ```

   验证码显示在后端日志中，有效期为两分钟；项目尚未发送短信，此方式仅适用于本地开发和演示。按 `Ctrl+C` 退出日志查看，不会停止服务。

3. 停止全部容器，保留数据库和 Redis 数据：

   ```bash
   ./hmdp-plus stop
   ```

4. 再次启动时执行 `./hmdp-plus`，脚本会重新构建并复用数据。若要删除容器和网络但保留数据卷，执行 `./hmdp-plus down`。

不要随意删除 `.env` 或数据卷：重新生成 JWT 密钥会使已签发的访问令牌失效；删除数据卷会清空数据库和 Redis。
