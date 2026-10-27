# 后端 HTTP 接口文档

本文依据 `backend/src/main/java/com/hmdp/controller/`、服务实现及配置整理，描述当前代码注册的接口。示例为说明格式的虚构数据，不代表数据库中存在这些记录。配置或代码变更后应同步核对本文。

## 通用约定

- 本地后端默认地址：`http://localhost:8081`，实际地址以部署配置为准。以下路径均相对于该地址，无需额外 `/api` 前缀；代理可能另行添加前缀。
- JSON 请求使用 `Content-Type: application/json`；图片上传使用 `multipart/form-data`。参数分别标为路径、查询或 JSON 正文；没有注明正文的接口无需正文。
- 登录后以 `Authorization: Bearer <accessToken>` 请求；服务也兼容直接传令牌。除下文标记“匿名”的路径及登录、刷新、退出外均需登录。特别注意 `/shop/**` 整组均被登录拦截器排除，包括新增和更新；`/blog/likes/{id}` 实际仍需登录，因为匿名匹配规则是 `/blog/*/likes`。此处描述现状，不代表权限设计建议。
- JWT 拦截器会检查传入的令牌和 Redis 会话；即使匿名接口，传入无效令牌也可能返回 401。未携带令牌访问受保护接口时，登录拦截器返回 HTTP 401，响应体可能为空；无效或失效令牌可返回 JSON 错误。
- 统一 JSON 包装 `Result`：`success` 表示业务成功，`errorMsg` 为失败说明，`data` 为结果，`total` 为可选总数。默认 Jackson 配置省略 null 字段，因此无数据成功通常为 `{"success":true}`，失败示例为 `{"success":false,"errorMsg":"手机号格式错误"}`。业务失败不一定对应非 2xx 状态，必须同时检查 `success`；部分请求解析错误由 Spring 默认处理，响应不一定是 `Result`。
- 下面展示的 `data` 示例均放在成功包装中，即 `{"success":true,"data":示例}`；空操作成功用 `{"success":true}`。`current` 从 1 开始，分页列表一般不返回总数；空列表为 `[]`。字段为 null 时可能省略。
- ID 在实体接口中通常是 JSON 数字；普通券购买和可靠秒杀结果的订单编号为字符串，避免浏览器整数精度损失。日期时间示例采用 `yyyy-MM-ddTHH:mm:ss`。秒杀活动时间由后端按 UTC 解释。

## 通用数据结构

字段是否实际出现取决于查询和 null 值，以下字段按实体定义列出，并非全部均由服务端强制校验。

| 对象 | 字段及含义 |
|---|---|
| UserDTO | `id` 用户编号；`nickName` 昵称；`icon` 头像 |
| UserInfo | `userId`、`city`、`introduce`、`fans`、`followee`、`gender`、`birthday`、`credits`、`level`；当前 Java 类型中 `gender`、`level` 为布尔值 |
| Shop | `id`、`name`、`typeId`、`images`（逗号分隔）、`area`、`address`、`x` 经度、`y` 纬度、`avgPrice`、`sold`、`comments`、`score`（评分乘 10）、`openHours`、`createTime`、`updateTime`；地理查询还可能返回 `distance`（米） |
| ShopType | `id`、`name`、`icon`、`sort`；创建和更新时间不返回 |
| Blog | `id`、`shopId`、`userId`、`icon`、`name`、`isLike`、`title`、`images`（逗号分隔）、`content`、`liked`、`comments`、`createTime`、`updateTime`；不同查询对作者和点赞状态的填充不同 |
| Voucher | `id`、`shopId`、`title`、`subTitle`、`rules`、`payValue`、`actualValue`、`type`、`status`、`stock`、`beginTime`、`endTime`、`createTime`、`updateTime`；`stock` 和活动时间是非表字段 |
## 接口详情

### 用户管理

#### 发送验证码

- **接口地址**：`POST /user/code`
- **认证**：匿名
- **请求参数**：

| 参数名 | 类型 | 位置 | 必填 | 说明 |
|---|---|---|---|---|
| phone | string | 查询 | 是 | 符合项目规则的手机号 |

- **请求示例**：

```http
POST /user/code?phone=13800138000
```

- **响应示例**：

```json
{
  "success": true
}
```

- **说明**：生成六位验证码并保存到 Redis、写入 debug 日志，当前没有实际短信发送实现。手机号格式错误时返回业务失败。

#### 登录

- **接口地址**：`POST /user/login`
- **认证**：匿名
- **请求参数**：

| 参数名 | 类型 | 位置 | 必填 | 说明 |
|---|---|---|---|---|
| phone | string | JSON | 是 | 手机号 |
| code | string | JSON | 是 | 短信验证码 |
| password | string | JSON | 否 | DTO 字段，当前不用于密码登录 |

- **请求示例**：

```http
POST /user/login
Content-Type: application/json

{
  "phone": "13800138000",
  "code": "123456"
}
```

- **响应示例**：

```json
{
  "success": true,
  "data": "<accessToken>"
}
```

- **说明**：新手机号自动创建用户。刷新凭证通过 Set-Cookie 写入 HttpOnly 的 hmdp_refresh；响应设置 Cache-Control: no-store。

#### 刷新令牌

- **接口地址**：`POST /user/refresh`
- **认证**：匿名
- **请求参数**：

| 参数名 | 类型 | 位置 | 必填 | 说明 |
|---|---|---|---|---|
| hmdp_refresh | string | Cookie | 是 | 登录或刷新时获得的刷新凭证 |
| X-Requested-With | string | 请求头 | 是 | 固定为 XMLHttpRequest |

- **请求示例**：

```http
POST /user/refresh
Cookie: hmdp_refresh=<refreshToken>
X-Requested-With: XMLHttpRequest
```

- **响应示例**：

```json
{
  "success": true,
  "data": "<newAccessToken>"
}
```

- **说明**：成功更新 Cookie；缺少指定请求头返回 HTTP 403，刷新凭证无效可返回 401。

#### 退出登录

- **接口地址**：`POST /user/logout`
- **认证**：匿名
- **请求参数**：

| 参数名 | 类型 | 位置 | 必填 | 说明 |
|---|---|---|---|---|
| Authorization | string | 请求头 | 条件必填 | 以访问令牌退出时使用 |
| X-Requested-With | string | 请求头 | 条件必填 | 使用 Cookie 撤销时必须为 XMLHttpRequest |
| hmdp_refresh | string | Cookie | 否 | 需要撤销的刷新凭证 |

- **请求示例**：

```http
POST /user/logout
Authorization: Bearer <accessToken>
```

- **响应示例**：

```json
{
  "success": true
}
```

- **说明**：没有指定请求头且没有授权头时返回 HTTP 403；成功清除刷新 Cookie。

#### 获取当前用户

- **接口地址**：`GET /user/me`
- **认证**：需要登录
- **请求参数**：

无。

- **请求示例**：

```http
GET /user/me
Authorization: Bearer <accessToken>
```

- **响应示例**：

```json
{
  "success": true,
  "data": {
    "id": 1,
    "nickName": "小明",
    "icon": "https://example.com/avatar.jpg"
  }
}
```

#### 根据编号查询用户

- **接口地址**：`GET /user/{id}`
- **认证**：需要登录
- **请求参数**：

| 参数名 | 类型 | 位置 | 必填 | 说明 |
|---|---|---|---|---|
| id | integer | 路径 | 是 | 用户编号 |

- **请求示例**：

```http
GET /user/1
Authorization: Bearer <accessToken>
```

- **响应示例**：

```json
{
  "success": true,
  "data": {
    "id": 1,
    "nickName": "小明",
    "icon": "https://example.com/avatar.jpg"
  }
}
```

- **说明**：用户不存在时成功但不含 data。

#### 根据编号查询用户详情

- **接口地址**：`GET /user/info/{id}`
- **认证**：需要登录
- **请求参数**：

| 参数名 | 类型 | 位置 | 必填 | 说明 |
|---|---|---|---|---|
| id | integer | 路径 | 是 | 用户编号 |

- **请求示例**：

```http
GET /user/info/1
Authorization: Bearer <accessToken>
```

- **响应示例**：

```json
{
  "success": true,
  "data": {
    "userId": 1,
    "city": "苏州",
    "introduce": "爱美食",
    "fans": 10,
    "followee": 5,
    "gender": false,
    "birthday": "2000-01-01",
    "credits": 100,
    "level": false
  }
}
```

- **说明**：不存在时成功但不含 data；创建、更新时间不返回。

#### 用户签到

- **接口地址**：`PUT /user/sign`
- **认证**：需要登录
- **请求参数**：

无。

- **请求示例**：

```http
PUT /user/sign
Authorization: Bearer <accessToken>
```

- **响应示例**：

```json
{
  "success": true
}
```

- **说明**：当天重复签到返回业务失败：今日已签到。

#### 查询连续签到天数

- **接口地址**：`GET /user/sign/count`
- **认证**：需要登录
- **请求参数**：

无。

- **请求示例**：

```http
GET /user/sign/count
Authorization: Bearer <accessToken>
```

- **响应示例**：

```json
{
  "success": true,
  "data": 3
}
```

- **说明**：无连续签到时为 0。

### 商铺管理

#### 查询商铺详情

- **接口地址**：`GET /shop/{id}`
- **认证**：匿名
- **请求参数**：

| 参数名 | 类型 | 位置 | 必填 | 说明 |
|---|---|---|---|---|
| id | integer | 路径 | 是 | 正数商铺编号 |

- **请求示例**：

```http
GET /shop/1
```

- **响应示例**：

```json
{
  "success": true,
  "data": {
    "id": 1,
    "name": "示例餐厅",
    "typeId": 1,
    "images": "https://example.com/a.jpg",
    "area": "中心商圈",
    "address": "示例路1号",
    "x": 120.6,
    "y": 31.3,
    "avgPrice": 80,
    "sold": 100,
    "comments": 20,
    "score": 45,
    "openHours": "10:00-22:00"
  }
}
```

- **说明**：编号非法或商铺不存在时返回业务失败。

#### 新增商铺

- **接口地址**：`POST /shop`
- **认证**：匿名
- **请求参数**：

| 参数名 | 类型 | 位置 | 必填 | 说明 |
|---|---|---|---|---|
| id | integer | JSON | 否 | 指定编号时必须为正数；省略由数据库生成 |
| name | string | JSON | 否 | 商铺名称 |
| typeId | integer | JSON | 否 | 商铺类型编号 |
| images | string | JSON | 否 | 图片 URL，多个以逗号分隔 |
| area | string | JSON | 否 | 商圈 |
| address | string | JSON | 否 | 地址 |
| x | number | JSON | 否 | 经度 |
| y | number | JSON | 否 | 纬度 |
| avgPrice | integer | JSON | 否 | 均价 |
| sold | integer | JSON | 否 | 销量 |
| comments | integer | JSON | 否 | 评论数 |
| score | integer | JSON | 否 | 评分乘以 10 |
| openHours | string | JSON | 否 | 营业时间 |
| createTime | string | JSON | 否 | 创建时间 |
| updateTime | string | JSON | 否 | 更新时间 |

- **请求示例**：

```http
POST /shop
Content-Type: application/json

{
  "name": "示例餐厅",
  "typeId": 1,
  "images": "https://example.com/a.jpg",
  "area": "中心商圈",
  "address": "示例路1号",
  "x": 120.6,
  "y": 31.3,
  "avgPrice": 80,
  "score": 45,
  "openHours": "10:00-22:00"
}
```

- **响应示例**：

```json
{
  "success": true,
  "data": 1
}
```

- **说明**：实体其他字段的数据库必填约束以实际表结构为准。

#### 更新商铺

- **接口地址**：`PUT /shop`
- **认证**：匿名
- **请求参数**：

| 参数名 | 类型 | 位置 | 必填 | 说明 |
|---|---|---|---|---|
| id | integer | JSON | 是 | 要更新的商铺编号 |
| name | string | JSON | 否 | 商铺名称 |
| typeId | integer | JSON | 否 | 商铺类型编号 |
| images | string | JSON | 否 | 图片 URL，多个以逗号分隔 |
| area | string | JSON | 否 | 商圈 |
| address | string | JSON | 否 | 地址 |
| x | number | JSON | 否 | 经度 |
| y | number | JSON | 否 | 纬度 |
| avgPrice | integer | JSON | 否 | 均价 |
| sold | integer | JSON | 否 | 销量 |
| comments | integer | JSON | 否 | 评论数 |
| score | integer | JSON | 否 | 评分乘以 10 |
| openHours | string | JSON | 否 | 营业时间 |
| createTime | string | JSON | 否 | 创建时间 |
| updateTime | string | JSON | 否 | 更新时间 |

- **请求示例**：

```http
PUT /shop
Content-Type: application/json

{
  "id": 1,
  "name": "新店名",
  "address": "新地址"
}
```

- **响应示例**：

```json
{
  "success": true
}
```

- **说明**：按当前 MyBatis 更新策略写入字段；缺少 id 返回业务失败。

#### 按类型查询商铺

- **接口地址**：`GET /shop/of/type`
- **认证**：匿名
- **请求参数**：

| 参数名 | 类型 | 位置 | 必填 | 说明 |
|---|---|---|---|---|
| typeId | integer | 查询 | 是 | 商铺类型编号 |
| current | integer | 查询 | 否 | 页码，默认 1 |
| x | number | 查询 | 否 | 经度，与 y 同时提供时按距离查询 |
| y | number | 查询 | 否 | 纬度，与 x 同时提供时按距离查询 |

- **请求示例**：

```http
GET /shop/of/type?typeId=1&current=1&x=120.6&y=31.3
```

- **响应示例**：

```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "name": "示例餐厅",
      "typeId": 1,
      "images": "https://example.com/a.jpg",
      "area": "中心商圈",
      "address": "示例路1号",
      "x": 120.6,
      "y": 31.3,
      "avgPrice": 80,
      "sold": 100,
      "comments": 20,
      "score": 45,
      "openHours": "10:00-22:00",
      "distance": 120.5
    }
  ]
}
```

- **说明**：每页 5 条；同时提供坐标时查 Redis GEO 中 5000 米内结果，按距离升序。缺少任一坐标时按类型查询。

#### 按名称查询商铺

- **接口地址**：`GET /shop/of/name`
- **认证**：匿名
- **请求参数**：

| 参数名 | 类型 | 位置 | 必填 | 说明 |
|---|---|---|---|---|
| name | string | 查询 | 否 | 名称关键字；非空时模糊匹配 |
| current | integer | 查询 | 否 | 页码，默认 1 |

- **请求示例**：

```http
GET /shop/of/name?name=餐厅&current=1
```

- **响应示例**：

```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "name": "示例餐厅",
      "typeId": 1,
      "images": "https://example.com/a.jpg",
      "area": "中心商圈",
      "address": "示例路1号",
      "x": 120.6,
      "y": 31.3,
      "avgPrice": 80,
      "sold": 100,
      "comments": 20,
      "score": 45,
      "openHours": "10:00-22:00"
    }
  ]
}
```

- **说明**：每页 10 条，name 缺省时不按名称过滤。

#### 查询商铺分类

- **接口地址**：`GET /shop-type/list`
- **认证**：匿名
- **请求参数**：

无。

- **请求示例**：

```http
GET /shop-type/list
```

- **响应示例**：

```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "name": "美食",
      "icon": "https://example.com/icon.png",
      "sort": 1
    }
  ]
}
```

### 探店笔记

#### 发布笔记

- **接口地址**：`POST /blog`
- **认证**：需要登录
- **请求参数**：

| 参数名 | 类型 | 位置 | 必填 | 说明 |
|---|---|---|---|---|
| id | integer | JSON | 否 | 笔记编号 |
| shopId | integer | JSON | 否 | 商铺编号 |
| userId | integer | JSON | 否 | 服务端覆盖为当前用户 |
| title | string | JSON | 否 | 标题 |
| images | string | JSON | 否 | 图片 URL，多个以逗号分隔 |
| content | string | JSON | 否 | 正文 |
| liked | integer | JSON | 否 | 点赞数 |
| comments | integer | JSON | 否 | 评论数 |
| createTime | string | JSON | 否 | 创建时间 |
| updateTime | string | JSON | 否 | 更新时间 |

- **请求示例**：

```http
POST /blog
Content-Type: application/json
Authorization: Bearer <accessToken>

{
  "shopId": 1,
  "title": "午餐推荐",
  "images": "https://example.com/a.jpg",
  "content": "味道很好"
}
```

- **响应示例**：

```json
{
  "success": true,
  "data": 1
}
```

- **说明**：服务端覆盖 userId，保存后向粉丝收件箱推送；实体和数据库约束决定实际必填字段。

#### 切换点赞状态

- **接口地址**：`PUT /blog/like/{id}`
- **认证**：需要登录
- **请求参数**：

| 参数名 | 类型 | 位置 | 必填 | 说明 |
|---|---|---|---|---|
| id | integer | 路径 | 是 | 笔记编号 |

- **请求示例**：

```http
PUT /blog/like/1
Authorization: Bearer <accessToken>
```

- **响应示例**：

```json
{
  "success": true
}
```

- **说明**：切换点赞或取消点赞；重复调用不是幂等的固定点赞操作。频繁操作可能失败。

#### 查询我的笔记

- **接口地址**：`GET /blog/of/me`
- **认证**：需要登录
- **请求参数**：

| 参数名 | 类型 | 位置 | 必填 | 说明 |
|---|---|---|---|---|
| current | integer | 查询 | 否 | 页码，默认 1 |

- **请求示例**：

```http
GET /blog/of/me?current=1
Authorization: Bearer <accessToken>
```

- **响应示例**：

```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "shopId": 1,
      "userId": 1,
      "name": "小明",
      "title": "午餐推荐",
      "images": "https://example.com/a.jpg",
      "content": "味道很好",
      "liked": 3,
      "comments": 0
    }
  ]
}
```

- **说明**：每页 10 条；作者、点赞状态等非数据库字段未必填充。

#### 查询热门笔记

- **接口地址**：`GET /blog/hot`
- **认证**：匿名
- **请求参数**：

| 参数名 | 类型 | 位置 | 必填 | 说明 |
|---|---|---|---|---|
| current | integer | 查询 | 否 | 页码，默认 1 |

- **请求示例**：

```http
GET /blog/hot?current=1
```

- **响应示例**：

```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "shopId": 1,
      "userId": 1,
      "name": "小明",
      "title": "午餐推荐",
      "images": "https://example.com/a.jpg",
      "content": "味道很好",
      "liked": 3,
      "comments": 0,
      "isLike": false
    }
  ]
}
```

- **说明**：每页 10 条，按点赞数降序。

#### 查询笔记详情

- **接口地址**：`GET /blog/{id}`
- **认证**：需要登录
- **请求参数**：

| 参数名 | 类型 | 位置 | 必填 | 说明 |
|---|---|---|---|---|
| id | integer | 路径 | 是 | 笔记编号 |

- **请求示例**：

```http
GET /blog/1
Authorization: Bearer <accessToken>
```

- **响应示例**：

```json
{
  "success": true,
  "data": {
    "id": 1,
    "shopId": 1,
    "userId": 1,
    "name": "小明",
    "title": "午餐推荐",
    "images": "https://example.com/a.jpg",
    "content": "味道很好",
    "liked": 3,
    "comments": 0
  }
}
```

- **说明**：不存在时返回业务失败：博客不存在。

#### 查询笔记点赞用户

- **接口地址**：`GET /blog/likes/{id}`
- **认证**：需要登录
- **请求参数**：

| 参数名 | 类型 | 位置 | 必填 | 说明 |
|---|---|---|---|---|
| id | integer | 路径 | 是 | 笔记编号 |

- **请求示例**：

```http
GET /blog/likes/1
Authorization: Bearer <accessToken>
```

- **响应示例**：

```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "nickName": "小明",
      "icon": "https://example.com/avatar.jpg"
    }
  ]
}
```

- **说明**：返回按点赞时间排序的最早最多 5 人，无记录为空数组。

#### 查询指定用户的笔记

- **接口地址**：`GET /blog/of/user`
- **认证**：需要登录
- **请求参数**：

| 参数名 | 类型 | 位置 | 必填 | 说明 |
|---|---|---|---|---|
| id | integer | 查询 | 是 | 用户编号 |
| current | integer | 查询 | 否 | 页码，默认 1 |

- **请求示例**：

```http
GET /blog/of/user?id=1&current=1
Authorization: Bearer <accessToken>
```

- **响应示例**：

```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "shopId": 1,
      "userId": 1,
      "name": "小明",
      "title": "午餐推荐",
      "images": "https://example.com/a.jpg",
      "content": "味道很好",
      "liked": 3,
      "comments": 0
    }
  ]
}
```

- **说明**：每页 10 条。

#### 查询关注用户的笔记

- **接口地址**：`GET /blog/of/follow`
- **认证**：需要登录
- **请求参数**：

| 参数名 | 类型 | 位置 | 必填 | 说明 |
|---|---|---|---|---|
| lastId | integer | 查询 | 是 | 毫秒时间戳上界 |
| offset | integer | 查询 | 否 | 相同时间戳的偏移量，默认 0 |

- **请求示例**：

```http
GET /blog/of/follow?lastId=1793000000000&offset=0
Authorization: Bearer <accessToken>
```

- **响应示例**：

```json
{
  "success": true,
  "data": {
    "list": [
      {
        "id": 1,
        "shopId": 1,
        "userId": 1,
        "name": "小明",
        "title": "午餐推荐",
        "images": "https://example.com/a.jpg",
        "content": "味道很好",
        "liked": 3,
        "comments": 0
      }
    ],
    "minTime": 1792999999000,
    "offset": 1
  }
}
```

- **说明**：每次最多 3 条；下一页使用返回的 minTime 和 offset；没有收件箱记录时成功但不含 data。

### 关注

#### 关注或取消关注

- **接口地址**：`PUT /follow/{id}/{isFollow}`
- **认证**：需要登录
- **请求参数**：

| 参数名 | 类型 | 位置 | 必填 | 说明 |
|---|---|---|---|---|
| id | integer | 路径 | 是 | 被关注用户编号 |
| isFollow | boolean | 路径 | 是 | true 关注，false 取消关注 |

- **请求示例**：

```http
PUT /follow/2/true
Authorization: Bearer <accessToken>
```

- **响应示例**：

```json
{
  "success": true
}
```

#### 判断是否关注

- **接口地址**：`GET /follow/or/not/{id}`
- **认证**：需要登录
- **请求参数**：

| 参数名 | 类型 | 位置 | 必填 | 说明 |
|---|---|---|---|---|
| id | integer | 路径 | 是 | 被查询用户编号 |

- **请求示例**：

```http
GET /follow/or/not/2
Authorization: Bearer <accessToken>
```

- **响应示例**：

```json
{
  "success": true,
  "data": true
}
```

#### 查询共同关注

- **接口地址**：`GET /follow/common/{id}`
- **认证**：需要登录
- **请求参数**：

| 参数名 | 类型 | 位置 | 必填 | 说明 |
|---|---|---|---|---|
| id | integer | 路径 | 是 | 另一用户编号 |

- **请求示例**：

```http
GET /follow/common/2
Authorization: Bearer <accessToken>
```

- **响应示例**：

```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "nickName": "小明",
      "icon": "https://example.com/avatar.jpg"
    }
  ]
}
```

- **说明**：无共同关注时返回空数组。

### 优惠券

#### 新增普通券

- **接口地址**：`POST /voucher`
- **认证**：需要登录
- **请求参数**：

| 参数名 | 类型 | 位置 | 必填 | 说明 |
|---|---|---|---|---|
| id | integer | JSON | 否 | 优惠券编号 |
| shopId | integer | JSON | 否 | 商铺编号 |
| title | string | JSON | 否 | 标题 |
| subTitle | string | JSON | 否 | 副标题 |
| rules | string | JSON | 否 | 使用规则 |
| payValue | integer | JSON | 否 | 支付金额 |
| actualValue | integer | JSON | 否 | 抵扣金额 |
| type | integer | JSON | 否 | 券类型 |
| status | integer | JSON | 否 | 状态 |
| stock | integer | JSON | 否 | 秒杀库存，普通券不使用 |
| beginTime | string | JSON | 否 | 秒杀开始时间，普通券不使用 |
| endTime | string | JSON | 否 | 秒杀结束时间，普通券不使用 |
| createTime | string | JSON | 否 | 创建时间 |
| updateTime | string | JSON | 否 | 更新时间 |

- **请求示例**：

```http
POST /voucher
Content-Type: application/json
Authorization: Bearer <accessToken>

{
  "shopId": 1,
  "title": "100元代金券",
  "payValue": 8000,
  "actualValue": 10000,
  "type": 0,
  "status": 1
}
```

- **响应示例**：

```json
{
  "success": true,
  "data": 1
}
```

- **说明**：金额单位以数据库业务约定为准；接口未提供独立字段校验契约。

#### 新增秒杀券

- **接口地址**：`POST /voucher/seckill`
- **认证**：需要登录
- **请求参数**：

| 参数名 | 类型 | 位置 | 必填 | 说明 |
|---|---|---|---|---|
| id | integer | JSON | 否 | 禁止指定编号 |
| shopId | integer | JSON | 否 | 商铺编号 |
| title | string | JSON | 否 | 标题 |
| subTitle | string | JSON | 否 | 副标题 |
| rules | string | JSON | 否 | 使用规则 |
| payValue | integer | JSON | 否 | 支付金额 |
| actualValue | integer | JSON | 否 | 抵扣金额 |
| type | integer | JSON | 否 | 券类型 |
| status | integer | JSON | 否 | 状态 |
| stock | integer | JSON | 是 | 非负整数，可为 0 |
| beginTime | string | JSON | 是 | 按 UTC 解释，去掉小数秒后结束时间必须晚于开始时间 |
| endTime | string | JSON | 是 | 按 UTC 解释，去掉小数秒后结束时间必须晚于开始时间 |
| createTime | string | JSON | 否 | 创建时间 |
| updateTime | string | JSON | 否 | 更新时间 |

- **请求示例**：

```http
POST /voucher/seckill
Content-Type: application/json
Authorization: Bearer <accessToken>

{
  "shopId": 1,
  "title": "秒杀券",
  "payValue": 5000,
  "actualValue": 10000,
  "type": 1,
  "status": 1,
  "stock": 100,
  "beginTime": "2026-11-01T00:00:00",
  "endTime": "2026-11-02T00:00:00"
}
```

- **响应示例**：

```json
{
  "success": true,
  "data": 2
}
```

- **说明**：不能指定 id。可靠组件启用时自动发布；如果券已创建但发布未确认，success 为 false，data 为原券编号字符串，应使用初始化接口重试。

#### 重试秒杀活动初始化

- **接口地址**：`POST /voucher/seckill/{voucherId}/initialize`
- **认证**：需要登录
- **请求参数**：

| 参数名 | 类型 | 位置 | 必填 | 说明 |
|---|---|---|---|---|
| voucherId | integer | 路径 | 是 | 原正数券编号 |

- **请求示例**：

```http
POST /voucher/seckill/2/initialize
Authorization: Bearer <accessToken>
```

- **响应示例**：

```json
{
  "success": true,
  "data": 2
}
```

- **说明**：已发布活动只核对，不重置库存；状态不允许或基础设施异常时失败。

#### 查询商铺优惠券

- **接口地址**：`GET /voucher/list/{shopId}`
- **认证**：匿名
- **请求参数**：

| 参数名 | 类型 | 位置 | 必填 | 说明 |
|---|---|---|---|---|
| shopId | integer | 路径 | 是 | 商铺编号 |

- **请求示例**：

```http
GET /voucher/list/1
```

- **响应示例**：

```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "shopId": 1,
      "title": "100元代金券",
      "payValue": 8000,
      "actualValue": 10000,
      "type": 0,
      "status": 1
    }
  ]
}
```

#### 购买普通券

- **接口地址**：`POST /voucher/{voucherId}/purchase`
- **认证**：需要登录
- **请求参数**：

| 参数名 | 类型 | 位置 | 必填 | 说明 |
|---|---|---|---|---|
| voucherId | integer | 路径 | 是 | 正数普通券编号 |

- **请求示例**：

```http
POST /voucher/1/purchase
Authorization: Bearer <accessToken>
```

- **响应示例**：

```json
{
  "success": true,
  "data": "123456789012345678"
}
```

- **说明**：首次创建未支付订单；同一用户重复提交找回原订单。编号非法 HTTP 400；不存在、不是普通券或暂不可购买为业务失败。本接口不完成支付。

### 可靠秒杀
仅当 `hmdp.seckill.reliable-components-enabled` 和 `hmdp.seckill.reliable-api-enabled` 均为 `true` 时注册。旧 `/voucher-order/seckill/{id}` 未注册。

#### 提交抢购

- **接口地址**：`POST /voucher-order/reliable/seckill/{voucherId}`
- **认证**：需要登录
- **请求参数**：

| 参数名 | 类型 | 位置 | 必填 | 说明 |
|---|---|---|---|---|
| voucherId | integer | 路径 | 是 | 正数秒杀券编号 |
| requestId | string | JSON | 是 | 1～128 位，仅字母、数字、下划线、连字符；同一次抢购重试必须沿用 |

- **请求示例**：

```http
POST /voucher-order/reliable/seckill/2
Content-Type: application/json
Authorization: Bearer <accessToken>

{
  "requestId": "purchase_20261101_001"
}
```

- **响应示例**：

```json
{
  "success": true,
  "data": {
    "orderId": "123456789012345678",
    "requestId": "purchase_20261101_001",
    "voucherId": "1",
    "status": "ACCEPTED",
    "statusDescription": "已受理"
  }
}
```

- **说明**：requestId 应在提交前生成并保存，不能绑定不同业务参数。返回当前持久化状态；ACCEPTED 不表示已建单。参数非法 HTTP 400，暂停接单 HTTP 503；响应丢失后沿用编号查询或重试。

#### 按订单编号查询抢购

- **接口地址**：`GET /voucher-order/reliable/orders/{orderId}`
- **认证**：需要登录
- **请求参数**：

| 参数名 | 类型 | 位置 | 必填 | 说明 |
|---|---|---|---|---|
| orderId | integer | 路径 | 是 | 正数原订单编号 |

- **请求示例**：

```http
GET /voucher-order/reliable/orders/123456789012345678
Authorization: Bearer <accessToken>
```

- **响应示例**：

```json
{
  "success": true,
  "data": {
    "orderId": "123456789012345678",
    "requestId": "purchase_20261101_001",
    "voucherId": "1",
    "status": "ACCEPTED",
    "statusDescription": "已受理"
  }
}
```

- **说明**：仅查询当前用户；不存在或无权访问均为 HTTP 404，参数非法 HTTP 400。

#### 按请求编号查询抢购

- **接口地址**：`GET /voucher-order/reliable/requests/{requestId}`
- **认证**：需要登录
- **请求参数**：

| 参数名 | 类型 | 位置 | 必填 | 说明 |
|---|---|---|---|---|
| requestId | string | 路径 | 是 | 原请求编号，格式同提交接口 |

- **请求示例**：

```http
GET /voucher-order/reliable/requests/purchase_20261101_001
Authorization: Bearer <accessToken>
```

- **响应示例**：

```json
{
  "success": true,
  "data": {
    "orderId": "123456789012345678",
    "requestId": "purchase_20261101_001",
    "voucherId": "1",
    "status": "ACCEPTED",
    "statusDescription": "已受理"
  }
}
```

- **说明**：仅查询当前用户；不存在或无权访问均为 HTTP 404。404 不能证明迟到请求不会执行。

`status` 状态：`PREPARING` 已登记但结果未确定；`ACCEPTED` 已受理、尚不表示建单；`SUCCEEDED` 已建单但不代表支付；`REJECTED` 被拒绝；`CLOSED` 已关闭，库存是否补偿需另行核对。存在原因时可能返回 `reason` 和 `reasonDescription`。

### 文件管理

#### 上传笔记图片

- **接口地址**：`POST /upload/blog`
- **认证**：需要登录
- **请求参数**：

| 参数名 | 类型 | 位置 | 必填 | 说明 |
|---|---|---|---|---|
| file | file | 表单 | 是 | multipart 文件，非空；单文件和整个请求上限均为 100 MB |

- **请求示例**：

```http
POST /upload/blog
Authorization: Bearer <accessToken>
Content-Type: multipart/form-data; boundary=<boundary>

file=@example.jpg
```

- **响应示例**：

```json
{
  "success": true,
  "data": "https://example.com/blogs/a/3/image.jpg"
}
```

- **说明**：控制器未实现文件类型校验；空文件、超限返回业务失败。

#### 删除笔记图片

- **接口地址**：`GET /upload/blog/delete`
- **认证**：需要登录
- **请求参数**：

| 参数名 | 类型 | 位置 | 必填 | 说明 |
|---|---|---|---|---|
| name | string | 查询 | 是 | 对象存储 URL，查询参数需 URL 编码 |

- **请求示例**：

```http
GET /upload/blog/delete?name=https%3A%2F%2Fexample.com%2Fblogs%2Fa%2F3%2Fimage.jpg
Authorization: Bearer <accessToken>
```

- **响应示例**：

```json
{
  "success": true
}
```

- **说明**：当前实现使用 GET 删除，删除失败返回业务失败。

### 秒杀运维
仅当可靠组件和 `hmdp.seckill.maintenance.operations-enabled` 同时开启时注册，运维接口默认关闭；独立令牌至少 32 字符。令牌错误返回 HTTP 403，未登录可能先返回 401。

#### 查询维护指标

- **接口地址**：`GET /ops/seckill/health`
- **认证**：登录及运维令牌
- **请求参数**：

| 参数名 | 类型 | 位置 | 必填 | 说明 |
|---|---|---|---|---|
| X-Seckill-Ops-Token | string | 请求头 | 是 | 独立运维令牌 |

- **请求示例**：

```http
GET /ops/seckill/health
Authorization: Bearer <accessToken>
X-Seckill-Ops-Token: <opsToken>
```

- **响应示例**：

```json
{
  "success": true,
  "data": {
    "pending_tasks": 0,
    "pending_create_events": 0,
    "pending_confirmations": 0,
    "pending_releases": 0,
    "expired_leases": 0,
    "max_attempts": 0,
    "oldest_task_seconds": 0,
    "unfinished_requests": 0,
    "preparing_requests": 0,
    "accepted_requests": 0,
    "oldest_request_seconds": 0,
    "quarantine_pending": 0,
    "pending_campaigns": 0,
    "oldest_campaign_seconds": 0,
    "blocked_campaigns": 0,
    "recovery_errors": 0
  }
}
```

- **说明**：年龄字段单位为秒；指标来自数据库，不代表 Broker 精确积压量。

#### 查询隔离消息摘要

- **接口地址**：`GET /ops/seckill/quarantine`
- **认证**：登录及运维令牌
- **请求参数**：

| 参数名 | 类型 | 位置 | 必填 | 说明 |
|---|---|---|---|---|
| X-Seckill-Ops-Token | string | 请求头 | 是 | 独立运维令牌 |

- **请求示例**：

```http
GET /ops/seckill/quarantine
Authorization: Bearer <accessToken>
X-Seckill-Ops-Token: <opsToken>
```

- **响应示例**：

```json
{
  "success": true,
  "data": [
    {
      "quarantine_id": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "source": "DLQ",
      "message_id": "msg-1",
      "topic": "topic",
      "body_hash": "<摘要>",
      "error_type": "INVALID",
      "reconsume_times": 1,
      "created_at": "2026-11-01T00:00:00"
    }
  ]
}
```

- **说明**：返回最早尚未处置记录，数量由维护批次配置限制；不返回消息正文。

#### 重投原建单事件

- **接口地址**：`POST /ops/seckill/orders/{orderId}/replay`
- **认证**：登录及运维令牌
- **请求参数**：

| 参数名 | 类型 | 位置 | 必填 | 说明 |
|---|---|---|---|---|
| X-Seckill-Ops-Token | string | 请求头 | 是 | 独立运维令牌 |
| orderId | integer | 路径 | 是 | 正数原订单编号 |
| reason | string | JSON | 是 | 操作原因，非空白且不超过 256 字符 |
| quarantineId | string | JSON | 否 | 可选隔离编号，64 位小写十六进制 |

- **请求示例**：

```http
POST /ops/seckill/orders/123456789012345678/replay
Content-Type: application/json
Authorization: Bearer <accessToken>
X-Seckill-Ops-Token: <opsToken>

{
  "reason": "已核对原订单",
  "quarantineId": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
}
```

- **响应示例**：

```json
{
  "success": true
}
```

- **说明**：核对账本、预占和原事件等条件通过后重新投递，不接收替换消息正文。

#### 关闭抢购请求

- **接口地址**：`POST /ops/seckill/orders/{orderId}/close`
- **认证**：登录及运维令牌
- **请求参数**：

| 参数名 | 类型 | 位置 | 必填 | 说明 |
|---|---|---|---|---|
| X-Seckill-Ops-Token | string | 请求头 | 是 | 独立运维令牌 |
| orderId | integer | 路径 | 是 | 正数原订单编号 |
| reason | string | JSON | 是 | 操作原因，非空白且不超过 256 字符 |

- **请求示例**：

```http
POST /ops/seckill/orders/123456789012345678/close
Content-Type: application/json
Authorization: Bearer <accessToken>
X-Seckill-Ops-Token: <opsToken>

{
  "reason": "人工核对后关闭"
}
```

- **响应示例**：

```json
{
  "success": true
}
```

- **说明**：成功只表示关闭及释放任务已提交，不代表库存已经回补。

#### 处置隔离消息

- **接口地址**：`POST /ops/seckill/quarantine/{id}/dismiss`
- **认证**：登录及运维令牌
- **请求参数**：

| 参数名 | 类型 | 位置 | 必填 | 说明 |
|---|---|---|---|---|
| X-Seckill-Ops-Token | string | 请求头 | 是 | 独立运维令牌 |
| id | string | 路径 | 是 | 64 位小写十六进制隔离编号 |
| reason | string | JSON | 是 | 操作原因，非空白且不超过 256 字符 |

- **请求示例**：

```http
POST /ops/seckill/quarantine/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/dismiss
Content-Type: application/json
Authorization: Bearer <accessToken>
X-Seckill-Ops-Token: <opsToken>

{
  "reason": "已人工确认无需重投"
}
```

- **响应示例**：

```json
{
  "success": true
}
```

- **说明**：只处置隔离待办，不改变请求、订单或库存。

## 其他说明

`BlogCommentsController` 当前没有接口方法。全局异常处理对一般运行时异常返回 `{"success":false,"errorMsg":"服务器异常"}`，并不保证所有异常具有同一状态码或响应结构。
