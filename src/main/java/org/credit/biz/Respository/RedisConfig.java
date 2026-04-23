package org.credit.biz.Respository;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

public class RedisConfig {
      @Bean
      public RedisCacheConfiguration cacheConfiguration() {
            return RedisCacheConfiguration.defaultCacheConfig()
               .entryTtl(Duration.ofHours(1)) 
        
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                new GenericJackson2JsonRedisSerializer()
        ));
}
}