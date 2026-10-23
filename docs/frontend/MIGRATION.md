# 前端迁移完成范围

本次完成消费端页面迁移，新增独立 React + TypeScript 项目。
原前端与后端代码均保留；此阶段完成源码对齐、预览及模拟接口验证，
真实 Spring Boot、Redis、数据库、OSS 与浏览器的联调安排在后续阶段。

## 页面对应关系

| 原页面 | 新页面 / 组件 | 完成内容 |
| --- | --- | --- |
| `index.html` | `/` | 分类、商家发现、热门探店、全局搜索 |
| `shop-list.html` | `/shops` | 分类与名称查询、真实定位、两种视图、分页 |
| `shop-detail.html` | `/shops/:id` | 相册、商家资料、代金券、限时抢购与状态 |
| `blog-detail.html` | `/stories/:id` | 独立阅读、相册、点赞、作者关注、关联商家 |
| `blog-edit.html` | `/stories/new` | 图片上传/移除、商家选择、草稿、预览和发布 |
| `info.html` | `/account` | 我的笔记、关注动态、签到与退出 |
| `other-info.html` | `/people/:id` | 作者资料、笔记、关注及共同关注 |
| `info-edit.html` | `/account?tab=info` | 个人资料查看；原后端没有资料更新接口 |
| `login.html` / `login2.html` | 全局登录弹窗 | 手机验证码、登录态、401 引导；保留原登录接口 |
| 新增内容入口 | `/stories` | 热门探店列表与分页 |

新页面使用已确认的 React 路由结构。目录重构后，旧 HTML 与资源完整迁到
`frontend/legacy/`；原 Nginx 的 `html/hmdp` 通过相对符号链接指向该目录。
未切换部署或修改页面地址；不支持符号链接的环境需把 Nginx root 指向 legacy。

## 消费端接口覆盖

以下27个方法与路径均在新项目中有对应调用。请求仍使用 `/api` 前缀、
原始 `authorization` 请求头、原参数和统一 `Result` 返回结构。

| 模块 | 原接口 | 对应位置 |
| --- | --- | --- |
| 用户 | `POST /user/code`、`POST /user/login` | 登录弹窗 |
| 用户 | `GET /user/me`、`POST /user/logout` | 登录态、个人中心 |
| 用户 | `GET /user/{id}`、`GET /user/info/{id}` | 作者主页、个人资料 |
| 用户 | `PUT /user/sign`、`GET /user/sign/count` | 每日签到 |
| 商家 | `GET /shop-type/list` | 首页、列表分类 |
| 商家 | `GET /shop/of/type`、`GET /shop/of/name` | 分类、定位、搜索、发布商家选择 |
| 商家 | `GET /shop/{id}` | 商家详情、笔记关联商家 |
| 优惠券 | `GET /voucher/list/{shopId}` | 商家优惠券 |
| 秒杀 | `POST /voucher-order/seckill/{id}` | 二次确认、提交反馈、完整返回编号 |
| 笔记 | `GET /blog/hot`、`GET /blog/{id}` | 列表和详情 |
| 笔记 | `PUT /blog/like/{id}`、`GET /blog/likes/{id}` | 点赞与点赞用户 |
| 笔记 | `GET /blog/of/me`、`GET /blog/of/user` | 个人与作者笔记 |
| 笔记 | `GET /blog/of/follow` | 关注动态游标分页 |
| 笔记 | `POST /blog` | 笔记发布 |
| 上传 | `POST /upload/blog`、`GET /upload/blog/delete` | 图片上传和草稿图片移除 |
| 关注 | `PUT /follow/{id}/{isFollow}`、`GET /follow/or/not/{id}` | 关注切换与状态 |
| 关注 | `GET /follow/common/{id}` | 共同关注 |

`POST /shop`、`PUT /shop`、`POST /voucher`、`POST /voucher/seckill`
属于商家与优惠券数据维护接口，原消费端也没有对应管理页面；
本次不新增商家后台或运营后台。

## 现有能力边界

- 原资料编辑页只有展示与读取，没有提交接口，新前端保留只读资料。
- 原密码登录入口共用 `/user/login`，后端当前实现没有密码校验流程，
  新前端提供已有的手机号验证码登录，不另造密码认证流程。
- 评论控制器没有具体接口；原页面写死的评论、榜单和粉丝占位不作为真实数据迁移。
- 原地图、消息底栏入口没有可用流程，不建立空页面或伪造数据。
- 普通券仅展示原接口提供的价格和规则；现有下单接口只支持秒杀。
- 没有购物车、支付、订单查询、资料修改接口，不呈现可完成这些操作的假入口。
- 预览模式的数据与真实接口分开，禁用服务端写操作。

## 本次验证

- TypeScript 检查与生产构建。
- 原商城页面与新增社区页面的浏览器回归。
- 分类、搜索、分页、相册、优惠券规则和状态。
- 登录请求参数、原始鉴权头及401登录引导。
- multipart 图片上传、图片移除、发布字段、发布成功与失败。
- 本机草稿恢复，退出编辑页与登录失效前保存。
- 点赞 Toggle、关注布尔值、同时间戳动态分页、签到与退出。
- 长整数订单编号、提交防重、异步订单结果的准确表达。
- 深浅主题、屏宽适配与 WCAG AA 自动检查。

所有自动化写操作使用模拟接口，没有执行真实短信发送、OSS 上传、发文、关注、签到或下单。

## 后续真实联调顺序

1. 启动后端依赖与现有 Spring Boot 服务，确认 `/api` 代理目标。
2. 检查分类、搜索、分页、商家图片与原 `/imgs` 图片路径。
3. 核对验证码实际接收渠道、登录、令牌有效期与401表现。
4. 验证 OSS 上传/删除、草稿恢复、笔记发布与详情。
5. 验证点赞、关注、共同关注、动态分页和签到。
6. 使用测试优惠券核对秒杀库存、重复下单、时间与订单异步处理。
7. 检查 Nginx 路由刷新、图片、上传大小和 API 转发，再决定部署切换。
