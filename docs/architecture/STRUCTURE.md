# 项目结构与重构对照

本次按职责整理目录和命名，保留 Controller / Service / Mapper 分层。
接口路径、数据库表、Redis Key 与 TTL 取值保持原样。
旧实现、注释代码、未引用的类和常量均保留，所有 `.gitignore` 的内容保持原样。

## 文档目录

详细说明统一放在 `docs/`，根目录 `README.md` 保留为项目入口。
文档导航见 [项目文档索引](../README.md)。

```text
docs/
├── README.md               文档索引
├── architecture/           架构资料、项目结构与重构对照
├── optimization/           项目优化记录
│   └── cache/              缓存优化与布隆过滤器阶段记录
├── frontend/               前端开发、迁移和资源来源
├── plan/                   既有方案与调研
└── review/                 既有项目演进复盘
```

已有学习笔记继续保留，后续新增文档放入对应主题目录。

## 后端目录

```text
backend/src/main/java/com/hmdp/
├── config/                 Spring 配置与配置属性
├── constants/              按功能划分的常量
├── controller/             HTTP 接口
├── dto/                    请求、响应及数据传输对象
├── entity/                 数据库实体
├── exception/              异常类型与全局异常处理器
├── infrastructure/
│   ├── cache/              Redis 缓存客户端与逻辑过期模型
│   ├── lock/               保留的自定义 Redis 锁实现
│   ├── redis/              Redis ID 生成器
│   └── storage/            对象存储接口与阿里云 OSS 实现
├── interceptor/            当前及保留的历史拦截器
├── mapper/                 MyBatis 数据访问
├── security/               JWT、登录会话、验证码与用户上下文
├── service/                业务服务接口
│   └── impl/               业务服务实现
└── utils/                  通用正则校验工具
```

生产源码与测试源码使用对应包路径。业务服务接口的 `I` 前缀暂时保留，
本次不调整实体名、Mapper XML、数据库表名或 API 路径。

## Java 文件迁移

以下路径均相对于 `com.hmdp` 包。

| 原位置 | 新位置 | 职责 |
| --- | --- | --- |
| `utils.CacheRebuildBusyException` | `exception.CacheRebuildBusyException` | 缓存重建繁忙异常 |
| `config.WebExceptionAdvice` | `exception.GlobalExceptionHandler` | 全局接口异常处理 |
| `utils.CacheClient` | `infrastructure.cache.RedisCacheClient` | Redis 缓存查询与重建 |
| `utils.CacheTtl` | `infrastructure.cache.CacheTtl` | 随机 TTL 计算 |
| `utils.RedisData` | `infrastructure.cache.LogicalExpireCacheEntry` | 逻辑过期缓存数据 |
| `utils.JwtUtils` | `security.JwtTokenService` | JWT 签发与校验 |
| `utils.LoginTokenStore` | `security.LoginSessionStore` | 登录会话创建、刷新和撤销 |
| `utils.LoginCodeVerifier` | `security.LoginCodeService` | 验证码存储、频控与校验 |
| `utils.UserHolder` | `security.UserContext` | 当前线程用户上下文 |
| `utils.PasswordEncoder` | `security.PasswordEncoder` | 保留的密码编码实现 |
| `utils.RedisIdWorker` | `infrastructure.redis.RedisIdGenerator` | Redis ID 生成 |
| `utils.ILock` | `infrastructure.lock.ILock` | 保留的旧锁接口 |
| `utils.SimpleRedisLock` | `infrastructure.lock.SimpleRedisLock` | 保留的旧锁实现 |
| `service.OssService` | `infrastructure.storage.ObjectStorageService` | 对象存储接口 |
| `service.impl.OssServiceImpl` | `infrastructure.storage.AliyunOssStorageService` | 阿里云 OSS 实现 |
| `utils.RegexPatterns` | `constants.RegexPatterns` | 正则表达式常量 |

OSS 的上传、删除和对象键解析方法全部保留，控制器通过新接口注入实现；
`OssProperties` 仍归配置包。

## 按功能拆分常量

统一放在 `com.hmdp.constants`，不再把常量混放在工具包。

| 文件 | 内容 |
| --- | --- |
| `AuthConstants` | 验证码、登录会话、保留的旧登录和 Session 常量 |
| `CacheConstants` | 商铺缓存键、空值缓存、TTL 和随机偏移 |
| `LockConstants` | 锁前缀、租期、旧锁持有者标识前缀 |
| `SeckillConstants` | 秒杀库存键与订单 Stream 名称 |
| `BlogConstants` | 点赞键与关注动态键 |
| `FollowConstants` | 用户关注集合键 |
| `ShopConstants` | 商铺 GEO 索引键 |
| `UserConstants` | 昵称前缀和签到键 |
| `PageConstants` | 默认和最大分页大小 |
| `StorageConstants` | 保留的旧本地上传路径 |
| `RegexPatterns` | 手机号、邮箱、密码、验证码表达式 |

原 `RedisConstants`、`SystemConstants` 也移到 `constants` 包并保留为
弃用的聚合入口，字段转引功能常量类，防止取值出现两份定义。
生产和测试代码均直接引用功能常量类；旧常量字段及原有说明仍可查阅。

## Lua 脚本

```text
backend/src/main/resources/lua/
├── auth/
│   ├── login-code-send.lua
│   ├── login-code-verify.lua
│   ├── login-session-create.lua
│   ├── login-session-rotate.lua
│   └── login-session-revoke.lua
├── lock/
│   └── unlock.lua
└── seckill/
    └── seckill.lua
```

脚本内容原样保留，只调整文件位置与 Java classpath 引用。

## 前端目录与文件迁移

```text
frontend/
├── web/                    原 web-mall，当前 React 项目
│   ├── src/
│   │   ├── api/
│   │   │   ├── http.ts     HTTP 传输、响应解析和 API 异常
│   │   │   ├── session.ts  会话刷新、登录退出和认证请求
│   │   │   ├── auth.ts     用户认证接口入口
│   │   │   ├── shops.ts    商铺与分类接口
│   │   │   ├── vouchers.ts 优惠券与秒杀接口
│   │   │   ├── blogs.ts    热门笔记接口
│   │   │   ├── community.ts 社区、发布、关注与签到接口
│   │   │   └── catalog.ts  聚合商铺、优惠券和热门笔记查询
│   │   ├── components/
│   │   │   ├── ui/         通用展示与弹窗组件
│   │   │   ├── shop/       商铺卡片与分类图标
│   │   │   ├── auth/       登录弹窗
│   │   │   ├── community/  保留的社区组件
│   │   │   └── layout.tsx  页面布局
│   │   ├── hooks/          useResource 等复用 Hook
│   │   ├── mocks/          原预览 JSON 数据
│   │   ├── pages/          页面与原有路由
│   │   ├── lib/            格式化与文本工具
│   │   ├── app-context.tsx 原 context.tsx，保留登录、主题和模式管理
│   │   └── types.ts        原类型定义
│   ├── public/             现有静态资源
│   ├── tests/              原有浏览器测试
│   └── deploy/             Nginx 配置示例
├── legacy/                 原 nginx-1.18.0/html/hmdp，完整保留
└── nginx-1.18.0/            现有运行环境
    └── html/hmdp -> ../../legacy
```

保留原 UI 函数、请求处理、预览数据、页面路由和 CSS，仅拆分文件及更新引用。
`node_modules`、已有构建产物与测试产物随目录保留；未调整忽略规则。
原 Nginx 配置未改动，旧源码入口用相对符号链接兼容；不支持符号链接的
环境需要把原 `root html/hmdp` 指向实际 legacy 目录。

## 验证方式

后端在 `backend/` 中运行目标单元测试，避免运行会写入本地业务数据的历史测试：

```bash
mvn -o test -Dtest=RedisCacheClientTest,ShopTypeServiceImplTest
```

当前环境的 Mockito 需要显式加载本地 Byte Buddy Java agent 才能运行，
可通过 Maven 的 `-DargLine=-javaagent:本地代理JAR路径` 指定。

前端在 `frontend/web/` 中运行：

```bash
npm run build
npm run test:e2e
```

默认测试端口为 5173。本次验证使用临时配置中的独立 5174 端口，
避免影响原有开发进程；该临时配置不进入项目。
