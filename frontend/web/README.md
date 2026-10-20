# 黑马点评 Web 商城

独立的 React + TypeScript 桌面前端。包含首页、商家列表、商家详情、探店列表与详情、
笔记发布、个人中心和作者主页。
旧前端源码保留在 `../legacy/`；`../nginx-1.18.0/` 仅保留运行环境及兼容入口。

## 本地运行

要求 Node.js 22.12+。

```bash
cd frontend/web
npm ci
npm run dev
```

- 设计预览：<http://127.0.0.1:5173/?preview=1>
- 实时接口：<http://127.0.0.1:5173/?preview=0>
- 商家列表：<http://127.0.0.1:5173/shops?type=1&preview=1>
- 商家详情：<http://127.0.0.1:5173/shops/1?preview=1>
- 探店笔记：<http://127.0.0.1:5173/stories?preview=1>
- 笔记详情：<http://127.0.0.1:5173/stories/4?preview=1>
- 发布笔记：<http://127.0.0.1:5173/stories/new?preview=1>
- 个人中心：<http://127.0.0.1:5173/account?preview=1>
- 作者主页：<http://127.0.0.1:5173/people/2?preview=1>

预览模式使用项目 SQL 中的商家资料、分类、探店与优惠券快照，图片为设计示意，
不会请求后端、登录或下单。模式在当前浏览器标签页内保留，顶部可随时切换。
新标签页未指定模式时默认使用实时接口。实时模式连接失败会明确显示错误，
不会自动混入预览数据。
预览模式可以编辑本机草稿、选择示例商家和添加本地预览图片，
不会发送图片上传、发布、点赞、关注或签到请求。示例个人中心使用原 SQL 中的
公开昵称与头像；个人详情资料缺失时不编造粉丝数等统计。

## 连接后端

默认由 Vite 将 `/api` 代理到 `http://127.0.0.1:8081`，并移除 `/api` 前缀。
如需修改目标，复制 `.env.example` 为 `.env.local`，调整 `API_PROXY_TARGET` 后重启。
浏览器请求保持同源；无需更改后端接口或跨域设置。

访问令牌仍放在 `sessionStorage.token`，通过原始 `authorization` 请求头发送。
后端用七天有效的 HttpOnly Cookie 保存刷新凭证，JavaScript 无法读取。
关闭页面后自动恢复会话，访问 JWT 临近两小时有效期时续签；401 后最多刷新并重试一次。
七天从登录起算，刷新不延长。多标签页通过 Web Locks 串行轮换 Cookie，并同步退出状态。
localStorage 只存 `hmdp-session` 恢复提示，不保存令牌。
所有请求处理 HTTP 错误与业务 `success: false`；刷新失效后清理令牌并打开登录框，
网络故障保留恢复机会。长整数 ID 使用 lossless-json 保留精度。

| 功能 | 后端接口 |
| --- | --- |
| 分类 | `GET /shop-type/list` |
| 分类列表 / 附近商家 | `GET /shop/of/type?typeId=&current=&x=&y=` |
| 商家搜索 / 全部列表 | `GET /shop/of/name?name=&current=` |
| 商家详情 | `GET /shop/{id}` |
| 商家优惠券 | `GET /voucher/list/{shopId}` |
| 热门探店 | `GET /blog/hot?current=1` |
| 手机验证码 | `POST /user/code?phone=` |
| 登录 | `POST /user/login`，JSON `{ phone, code }` |
| 自动续签 / 恢复 | `POST /user/refresh`，携带 Cookie 与 `X-Requested-With` |
| 当前用户 / 退出 | `GET /user/me` / `POST /user/logout` |
| 优惠券秒杀 | `POST /voucher-order/seckill/{id}` |
| 笔记详情 / 点赞用户 | `GET /blog/{id}` / `GET /blog/likes/{id}` |
| 点赞切换 | `PUT /blog/like/{id}` |
| 我的笔记 / 作者笔记 | `GET /blog/of/me?current=` / `GET /blog/of/user?id=&current=` |
| 关注动态 | `GET /blog/of/follow?lastId=&offset=` |
| 用户 / 个人资料 | `GET /user/{id}` / `GET /user/info/{id}` |
| 关注状态 / 共同关注 | `GET /follow/or/not/{id}` / `GET /follow/common/{id}` |
| 关注或取消 | `PUT /follow/{id}/{true或false}` |
| 签到 / 连续签到 | `PUT /user/sign` / `GET /user/sign/count` |
| 上传 / 移除草稿图片 | `POST /upload/blog` / `GET /upload/blog/delete?name=` |
| 发布笔记 | `POST /blog`，JSON `{ title, content, images, shopId }` |

前端仅传入后端已支持的筛选参数。后端未提供总条数，列表使用上一页 / 下一页，
最后一页恰好满页时，下一页可能为空，空状态可返回上一页。
附近查询使用浏览器实际定位，仅对单个分类开放；未授权时不会使用虚构坐标。

普通优惠券展示价格与规则，不提供未实现的普通下单入口。限时抢购有二次确认、
提交中禁用和错误反馈，成功返回编号只表示请求受理，不表示支付或订单落库成功。
不提供当前后端尚未支持的购物车、支付、订单查询、评分排序、评价详情等入口。
手机号验证码发送渠道取决于现有后端配置。

原后端将笔记详情、作者资料、个人中心等接口设为需要登录；新前端保留该约定，
未登录时展示登录引导。热门笔记列表与商家浏览仍可公开访问。

## 笔记与个人中心

- 探店详情采用独立阅读页，可切换照片、查看关联商家、复制链接、点赞和关注作者。
- 点赞接口保持 Toggle 语义；提交中禁用按钮，提交后重新查询后端状态。
- 关注动态使用原 `lastId` / `offset` 游标，同时间戳分页累计 offset 并按 ID 去重。
- 个人中心包含个人笔记、关注动态、只读资料和签到。不提供无后端接口的资料编辑。
- 发布表单最多9张照片，单张10MB；上传使用原 `file` 字段和浏览器生成的 multipart boundary。
- 正文转换为转义后的换行 HTML，兼容原前端；阅读时解析为纯文本，不直接渲染用户 HTML。
- 发布前校验内容及图片地址组合长度，与原数据库 2048 字符字段对齐。
- 标题、正文、关联商家和已上传图片链接自动保存在当前浏览器的本机草稿中，
  按数据模式与登录用户隔离。离开编辑页或登录失效时尽力保存，发布失败不会清空。
- 发布成功后清理对应草稿，跳转新笔记详情。草稿图片移除沿用原删除接口。
- 预览模式的本地图片使用临时 object URL，刷新后须重新选择；不发送到服务器。

## 设计规范

- 桌面优先，内容宽度 1280px；超宽屏为 1320px。
- 橙色品牌，浅灰背景，语义颜色变量，系统深浅主题与手动切换。
- 按钮按用途分级：导航/分页32px、普通操作40px、主要提交44px；导航入口保留44px点击区域，小屏操作按钮至少44px。
- 图标全部来自 `@phosphor-icons/react`，没有 emoji 图标或手绘 SVG。
- 弹窗使用 Radix Dialog，提供焦点约束、Escape 关闭和可访问名称。
- 图片预留尺寸、失败占位，正文图片懒加载，主视觉优先加载。
- 页面路由拆包，加载 / 空 / 错误状态，键盘焦点和减少动态效果适配。
- 小屏提供响应式收敛，使用桌面网站的导航结构，无底部 H5 Tab 栏。

## 构建与部署

```bash
npm run build
npm run preview
```

发布 `dist/` 即可，开发服务器代理不会进入生产构建。
`deploy/nginx.conf.example` 提供与原 8081 / 8082 上游一致的反向代理示例，
支持前端路由刷新、原 `/api` 接口和本地 `/imgs` 资源。
部署时调整 root 路径；示例未自动应用，不影响现有运行中的 Nginx。

## 验证

```bash
npm run build
npm run test:e2e
```

浏览器测试默认使用 macOS 已安装的 Google Chrome。其他系统可设置
`CHROME_PATH=/path/to/chrome`。
测试覆盖页面导航、搜索、翻页、相册、规则、空状态、375/768/1024/1440 宽度、
深浅主题与 axe WCAG AA 检查，以及模拟后端的接口路径、鉴权、
401、秒杀状态、防重复提交和长订单编号。测试不会向真实后端提交订单。
社区测试覆盖 multipart、草稿恢复与发布失败保留、发布成功跳转、点赞关注、
动态游标、签到退出及未登录保护。社区写操作同样全部使用模拟接口。

截图保存在忽略的 `test-results/screenshots/` 目录。
生产环境性能可在 `npm run preview` 后用 Lighthouse 检查。

## 目录

```text
src/
  api/              HTTP、登录会话及按业务拆分的接口
  components/       ui、shop、auth、community 与页面布局
  pages/            商城、探店、发布、个人中心与作者主页
  hooks/            异步资源状态
  lib/              格式化工具
  app-context.tsx   数据模式、主题、登录状态
  mocks/            示例资料与作者快照
  styles.css        主题与响应式样式
  community.css     社区页面布局，复用相同主题变量
public/             本地图片与生成的首页主视觉
tests/              浏览器与接口契约验证
deploy/             Nginx 配置示例
```

图片来源及主视觉生成提示词见 `ASSETS.md`。
页面迁移对应关系、消费端接口覆盖与后续真实联调顺序见 `MIGRATION.md`。
