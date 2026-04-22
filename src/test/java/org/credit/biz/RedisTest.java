package org.credit.biz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@SpringBootTest
class RedisTest {
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Test
    void testRedisReadWrite() {
        // 1. 准备数据
        String key = "test:user:1001";
        String value = "xxxxx";

        // 获取操作对象
        ValueOperations<String, Object> operations = redisTemplate.opsForValue();

        // 2. 写入数据 (设置过期时间为 10 分钟)
        System.out.println("🟢 正在写入数据: " + key + " = " + value);
        operations.set(key, value, 10, TimeUnit.MINUTES);

        // 3. 读取数据
        System.out.println("🔵 正在读取数据: " + key);
        Object result = operations.get(key);

        // 4. 断言验证 (JUnit 5)
        // 验证读取到的值不为空
        assertNotNull(result, "读取到的数据不应为空");
        // 验证读取到的值与写入的值一致
        assertEquals(value, result, "读取到的数据与写入的不一致");

        System.out.println("🟡 读取结果: " + result);
        System.out.println("✅ Redis 读写测试通过！");
    }

}
