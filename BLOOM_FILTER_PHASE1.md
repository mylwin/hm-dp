# 商铺布隆过滤器：第一阶段实现与原因

## 本阶段行为

第一阶段只增加配置、全量 ID 扫描、就绪状态和查询观察。布隆判断结果
不会用于返回“商铺不存在”，不会修改新增、更新商铺流程。
现有空值缓存、随机 TTL、商铺分布式锁、二次检查和回源并发限制均保留。

## 修改前后

修改前，商铺详情直接使用互斥缓存查询：

```java
redisCacheClient.queryWithMutex(CACHE_SHOP_KEY, id, Shop.class,
        this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
```

修改后，额外传入只观察的回调：

```java
redisCacheClient.queryWithMutex(CACHE_SHOP_KEY, id, Shop.class,
        this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES,
        shopBloomFilter::observe);
```

回调放在首次缓存读取之后、获取商铺锁之前。正常缓存和空值标记命中
均不执行它，避免让缓存命中的热门请求增加布隆查询开销。
原方法签名保留，委托新重载并传入空回调，其他调用方不必接入商铺逻辑。
通用缓存客户端不依赖 `ShopBloomFilter`，业务服务负责选择观察组件。

`Consumer<ID>` 没有判断返回值，缓存客户端不会根据布隆结果提前返回；
`ShopBloomFilter.observe()` 返回的枚举在方法引用转换时被丢弃。
观察组件内部捕获运行时异常并累计计数，观察失败也继续原有查询。

## 文件职责

| 文件 | 职责 |
| --- | --- |
| `config/ShopBloomProperties.java` | 配置绑定、默认值和合法性校验 |
| `constants/ShopConstants.java` | 布隆过滤器和状态键的前缀 |
| `infrastructure/cache/bloom/ShopBloomFilter.java` | 读取就绪快照、判断并计数、原子发布元数据 |
| `infrastructure/cache/bloom/ShopBloomInitializer.java` | 启动构建锁、ID 分批导入、成功发布与失败清理 |
| `mapper/ShopMapper.java` | 查询最大 ID、按游标读取 ID；只读 SQL，不修改表结构 |
| `infrastructure/cache/RedisCacheClient.java` | 新增可选的缓存未命中观察重载 |
| `service/impl/ShopServiceImpl.java` | 将商铺观察接入详情查询 |
| `application.yaml` | 配置及环境变量入口 |

测试覆盖配置绑定与非法值、就绪判断、正负观察、并发初始化互斥、批次顺序、
空数据库、容量溢出、扫描失败、异常游标、中断与清理失败，以及负向观察不拦截业务。

## 为什么这样构建

1. **使用 Redisson 的 `RBloomFilter<Long>`**：复用现有依赖，无需 RedisBloom 模块。
   明确使用 `LongCodec`，使 ID 的编码在不同实例间一致。
2. **分布式构建锁**：同版本只有一个实例构建，其他实例不等待构建锁。
   不指定固定租期，使用 Redisson 看门狗续期。
3. **只查 ID**：不用加载商铺名称、图片、正文等完整实体，减少传输和内存占用。
4. **固定扫描上界**：构建开始时读取最大 ID，之后查询 `id > cursor AND id <= upperBound`，
   防止持续新增导致构建范围无界增长。这是有上界的扫描，不是数据库事务一致性快照。
5. **游标分页**：每批只读取有限行，避免一次性加载全部 ID，也避免大 OFFSET 扫描。
6. **独立候选 Key**：每次构建使用带 UUID 的新 Key，不在查询正在使用的过滤器上清空重建。
7. **完成后发布**：只有所有批次成功才将包含过滤器名的 READY 元数据用一次 SET 发布。
   BUILDING、FAILED 状态不提供可观察的就绪过滤器，避免使用半成品。
8. **容量检查**：加载数量超过配置预期容量时不发布，提示调整容量和版本，避免宣称仍满足目标误判率。
9. **失败清理**：正常异常路径清理自己的候选过滤器，并释放仍由当前线程持有的构建锁。
   进程强制退出可能遗留候选 Key，本阶段未实现定时孤儿 Key 回收。
10. **复用已就绪快照**：同版本、同容量及误判率的快照存在时，不在每个实例启动时重复全表扫描。
    需要重新扫描时调整版本；不自动删除旧版本，以免影响仍在使用它的实例。

## 状态和观察结果

状态键为 `hmdp:bloom:shop:state:<version>`，使用 String 类型存储 JSON：

```json
{
  "status": "READY",
  "filterName": "hmdp:bloom:shop:v1:<随机UUID>",
  "expectedInsertions": 100000,
  "falseProbability": 0.001,
  "loadedCount": 123,
  "upperBound": 150,
  "builtAt": "<构建完成UTC时间>"
}
```

`loadedCount` 是读取并加入的 ID 数量，不使用布隆过滤器的概率估计值当作数据库行数。

`ShopBloomFilter.observe(id)` 返回：

| 结果 | 含义 | 对原查询的影响 |
| --- | --- | --- |
| `DISABLED` | 配置关闭 | 无 |
| `NOT_READY` | 无就绪快照、参数不匹配、过滤器缺失或 ID 不适合观察 | 无 |
| `MIGHT_EXIST` | 过滤器判断可能包含 | 无 |
| `ABSENT` | 过滤器判断不包含 | 无，仍可查库并返回真实商铺 |
| `FAILED` | 可选观察发生异常 | 无，继续原有查询 |

`statistics()` 返回当前实例累计的可能包含、不包含、未就绪和观察失败次数，
可在调试时查看，也可供后续监控接入。计数不持久化，不代表线上实际拦截数，
本阶段没有新增管理 HTTP 接口，也不逐请求打印日志。
过滤器句柄按名称在本地复用，减少 Redisson 反复读取过滤器配置；
观察仍会产生额外 Redis 访问，本阶段未进行性能压测。

## 配置

| 配置项（`hmdp.cache.shop-bloom` 下） | 环境变量 | 默认值 | 约束 |
| --- | --- | --- | --- |
| `enabled` | `SHOP_BLOOM_ENABLED` | `true` | 关闭后不构建、不访问布隆状态 |
| `version` | `SHOP_BLOOM_VERSION` | `v1` | 1 至 32 位字母、数字、下划线或短横线 |
| `expected-insertions` | `SHOP_BLOOM_EXPECTED_INSERTIONS` | `100000` | 正数 |
| `false-probability` | `SHOP_BLOOM_FALSE_PROBABILITY` | `0.001` | 有限数，严格大于 0、小于 1 |
| `batch-size` | `SHOP_BLOOM_BATCH_SIZE` | `500` | 1 至 10000 |

配置在启动时绑定，修改后需要重启。容量与误判率为初始示例配置，需结合实际数据规模调整。
没有 `enforce` 开关，防止在后续一致性处理完成前误启用拦截。

## 后续边界

`READY` 只表示该次扫描完成。当前没有接入新增商铺同步、导入数据同步、
事务与空值缓存清理，也没有保证扫描期间数据库不变。新数据可能被观察为不包含。
下一阶段需要先解决这些问题，再考虑负向拦截。
本阶段保留全部原有业务和学习代码，不修改 `.gitignore`。
