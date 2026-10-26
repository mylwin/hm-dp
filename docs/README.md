# 项目文档索引

项目说明、设计方案、优化记录和前端资料统一放在 `docs/`，按主题归类。
项目根目录保留 `README.md` 作为使用入口，代理规则文件保留在根目录。

## 项目优化

- [项目优化建议](项目优化建议.md)
- [RocketMQ 秒杀订单迁移方案（设计与验收计划）](plan/rocketmq-seckill-design.md)

### 订单优化

- [先读总设计：完整业务、状态设计、数据库、Redis、流程时序与 44 个并发故障场景](architecture/ROCKETMQ_ORDER_DESIGN.md)
- [订单 RocketMQ 第一阶段：客户端接入与收发验证](optimization/order/ROCKETMQ_PHASE1.md)
- [订单 RocketMQ 第二阶段：可靠受理、预占与 Outbox](optimization/order/ROCKETMQ_PHASE2.md)
- [订单 RocketMQ 第二阶段图解与复盘：参考来源、架构、表职责、Redis 键值示例与故障恢复](optimization/order/ROCKETMQ_PHASE2_REVIEW.md)
- [订单 RocketMQ 第二阶段：数据设定、事务级别与并发边界](optimization/order/ROCKETMQ_PHASE2_TRANSACTIONS.md)
- [订单 RocketMQ 第三阶段：幂等建单、结果查询与真实前后端联调（含 PREPARING 停留定位）](optimization/order/ROCKETMQ_PHASE3.md)
- [订单 RocketMQ 第四阶段第 1 步：新秒杀券自动发布与原券初始化重试](optimization/order/ROCKETMQ_PHASE4_1.md)
- [订单 RocketMQ 第四阶段：自动发布、预占补偿、请求恢复、死信隔离与联合验收](optimization/order/ROCKETMQ_PHASE4.md)
- [订单 RocketMQ 第四阶段流程图解：16 个独立 Mermaid 流程](optimization/order/ROCKETMQ_PHASE4_FLOWS.md)
- [订单 RocketMQ 第四阶段新增 Lua：初始化、确认释放和预占核对 Mermaid 图](optimization/order/ROCKETMQ_PHASE4_LUA_FLOWS.md)
- [订单 RocketMQ 第五阶段：新路径启用、暂停提交与故障并发验收](optimization/order/ROCKETMQ_PHASE5.md)
- [创建测试券并从购买验证 MQ 建单（含 SQL、Redis 初始化及 HTTP 请求）](optimization/order/ROCKETMQ_PURCHASE_TEST.md)

### 缓存优化

- [商铺布隆过滤器第一阶段：快照构建与观察](optimization/cache/BLOOM_FILTER_PHASE1.md)
- [商铺布隆过滤器第二阶段：新增同步与空值缓存清理](optimization/cache/BLOOM_FILTER_PHASE2.md)
- [商铺布隆过滤器第三阶段：可配置负向拦截与空值兜底](optimization/cache/BLOOM_FILTER_PHASE3.md)
- [商铺布隆过滤器第四阶段：受控重建与构建状态维护](optimization/cache/BLOOM_FILTER_PHASE4.md)

阶段文档记录对应阶段的实现和限制；当前行为以最新阶段与实际代码为准。

## 架构与项目结构

- [RocketMQ 秒杀订单整体设计](architecture/ROCKETMQ_ORDER_DESIGN.md)
- [项目结构与文件迁移对照](architecture/STRUCTURE.md)
- [项目架构说明](项目架构说明.md)
- [项目架构源文件](项目架构.drawio)
- [架构可视化](architecture/hm-dp-architecture.html)

## 前端

- [前端开发、接口与部署说明](frontend/README.md)
- [秒杀维护页：本地开关、运维接口和验证说明](frontend/SECKILL_MAINTENANCE.md)
- [页面迁移与接口覆盖](frontend/MIGRATION.md)
- [图片与图标来源](frontend/ASSETS.md)

## 既有分析与规划

- [Bug 记录](Bug记录.md)
- [Redisson 源码分析](Redisson源码分析.md)
- [方案与调研目录](plan/)
- [项目演进复盘](review/hm-dp-backend-evolution.html)

已有资料继续保留原内容；后续新增文档应放入对应主题目录。
