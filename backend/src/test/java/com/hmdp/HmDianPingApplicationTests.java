package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.infrastructure.redis.RedisIdGenerator;
import com.hmdp.service.impl.ShopServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static com.hmdp.constants.ShopConstants.*;

@SpringBootTest
class HmDianPingApplicationTests {
    @Autowired
    private ShopServiceImpl shopService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedisIdGenerator redisWorker;

    private ExecutorService es = Executors.newFixedThreadPool(300);

    @Test
    void genId() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);

        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisWorker.getNextId("order");
                System.out.println("id = " + id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end - begin));
    }

    /**
     * 加载店铺数据到redis
     */
    @Test
    void loadShopData(){
        // 1.查询店铺数据
        List<Shop> shops = shopService.list();

        // 2.将店铺按照typeId进行分组
        Map<Long, List<Shop>> list = shops.stream().collect(Collectors.groupingBy(Shop::getTypeId));

        // 3.将数据写入redis
        list.forEach((key, value) -> {
            // 3.1 店铺类型为key
            String geoKey = SHOP_GEO_KEY + key;

            // 3.2 将店铺信息保存到一个集合中
            List<RedisGeoCommands.GeoLocation<String>> geoLocations = value.stream()
                    .map(shop -> new RedisGeoCommands.GeoLocation<>(shop.getId().toString(), new Point(shop.getX(), shop.getY())))
                    .toList();

            // 3.3 写入redis
            stringRedisTemplate.opsForGeo().add(geoKey, geoLocations);
        });
    }

    /**
     * 测试HyperLogLog
     */
    @Test
    void testHyperLogLog(){
        String [] values = new String[1000];
        int j = 0;
        for (int i = 0; i < 1000000; i++) {
            j = i % 1000;
            values[j] = "user_" + i;
            if(j == 999){
                // 每1000个元素执行一次
                stringRedisTemplate.opsForHyperLogLog().add("hmdp:hll_test", values);
            }
        }
        // 获取HyperLogLog的 cardinality
        Long size = stringRedisTemplate.opsForHyperLogLog().size("hmdp:hll_test");
        System.out.println("size = " + size);
    }
}
