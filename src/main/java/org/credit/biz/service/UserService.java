package org.credit.biz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper; // 引入 MP 的条件构造器
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.credit.biz.common.Result;
import org.credit.biz.constant.AuthHandlerConstant;
import org.credit.biz.mapper.UserMapper; // 1. 引入 Mapper
import org.credit.biz.model.User;
import org.credit.biz.model.UserProfile;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;


@Service
@Slf4j
@RequiredArgsConstructor  // Lombok 会自动生成 final 字段的构造函数
public class UserService {

    // 1. 删除模拟数据库，注入 UserMapper (Spring Boot 会自动把 Mapper 的代理对象注入进来)
    private final UserMapper userMapper;
    
    private final AuthHandlerConstant constant;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    private static final Duration USER_PROFILE_CACHE_TTL = Duration.ofMinutes(10);
    private static final Duration USER_PROFILE_NULL_CACHE_TTL = Duration.ofMinutes(2);
    private static final String USER_PROFILE_CACHE_PREFIX = "user:profile:";
    private static final String USER_PROFILE_LOCK_PREFIX = "lock:user:profile:";
    private static final String NULL_CACHE_VALUE = "__NULL__";
    private static final Duration LOCK_TTL = Duration.ofSeconds(5);
    private static final int MAX_CACHE_REBUILD_CONCURRENCY = 5;
    private static final int REDIS_FAILURE_THRESHOLD = 3;
    private static final long REDIS_CIRCUIT_BREAKER_MILLIS = 30_000L;
    private static final long DOUBLE_DELETE_DELAY_MILLIS = 500L;
    private static final String USER_EMAIL_BLOOM_FILTER_KEY = "bf:user:email";
    private static final double BLOOM_FILTER_ERROR_RATE = 0.01D;
    private static final long BLOOM_FILTER_INITIAL_CAPACITY = 100_000L;

    private final Semaphore cacheRebuildSemaphore = new Semaphore(MAX_CACHE_REBUILD_CONCURRENCY);
    private final AtomicInteger redisFailureCounter = new AtomicInteger(0);
    private volatile long redisCircuitOpenUntil = 0L;
    private volatile boolean bloomFilterReady = false;
    private final ScheduledExecutorService cacheDeleteExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "user-cache-double-delete");
        thread.setDaemon(true);
        return thread;
    });

    private String buildUserProfileCacheKey(String email) {
        return USER_PROFILE_CACHE_PREFIX + email;
    }

    private String buildUserProfileLockKey(String email) {
        return USER_PROFILE_LOCK_PREFIX + email;
    }

    @PostConstruct
    public void initializeBloomFilter() {
        try {
            ensureRedisBloomFilter();
            List<User> users = userMapper.selectList(null);
            if (users == null || users.isEmpty()) {
                bloomFilterReady = true;
                return;
            }
            for (User user : users) {
                if (user != null && user.getEmail() != null && !user.getEmail().isBlank()) {
                    addEmailToBloomFilter(user.getEmail());
                }
            }
            bloomFilterReady = true;
            log.info("Bloom filter initialized. userCount={}", users.size());
        } catch (Exception e) {
            bloomFilterReady = false;
            log.warn("Bloom filter initialization skipped because user list could not be loaded.", e);
        }
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private Object executeRedisCommand(String stage, RedisCallback<Object> callback) {
        if (isRedisCircuitOpen()) {
            return null;
        }
        try {
            Object result = stringRedisTemplate.execute(callback);
            recordRedisSuccess();
            return result;
        } catch (Exception e) {
            recordRedisFailure(stage, e);
            return null;
        }
    }

    private void ensureRedisBloomFilter() {
        Object result = executeRedisCommand("ensureRedisBloomFilter", connection -> {
            try {
                return connection.execute(
                    "BF.RESERVE",
                    bytes(USER_EMAIL_BLOOM_FILTER_KEY),
                    bytes(String.valueOf(BLOOM_FILTER_ERROR_RATE)),
                    bytes(String.valueOf(BLOOM_FILTER_INITIAL_CAPACITY))
                );
            } catch (DataAccessException e) {
                return null;
            }
        });
        if (result != null) {
            bloomFilterReady = true;
        }
    }

    private Result<User> loginDatabaseError(String email, Exception e) {
        log.error("Login database access failed. email={}", email, e);
        return new Result<>(500, "登录失败，数据库连接或配置异常", null);
    }

    private Result<Void> registerDatabaseError(String email, Exception e) {
        log.error("Register database access failed. email={}", email, e);
        return new Result<>(500, "注册失败，数据库连接或配置异常", null);
    }

    private Result<UserProfile> userProfileDatabaseError(String email, Exception e) {
        log.error("User profile database access failed. email={}", email, e);
        return new Result<>(500, "查询用户信息失败，数据库连接或配置异常", null);
    }

    private Result<Void> updateUserDatabaseError(String email, Exception e) {
        log.error("Update user database access failed. email={}", email, e);
        return new Result<>(500, "更新用户信息失败，数据库连接或配置异常", null);
    }

    private Result<Void> deleteUserDatabaseError(String email, Exception e) {
        log.error("Delete user database access failed. email={}", email, e);
        return new Result<>(500, "删除用户信息失败，数据库连接或配置异常", null);
    }

    private Duration buildRandomizedProfileTtl() {
        long jitterMinutes = ThreadLocalRandom.current().nextLong(0, 6);
        return USER_PROFILE_CACHE_TTL.plusMinutes(jitterMinutes);
    }

    private Duration buildRandomizedNullCacheTtl() {
        long jitterSeconds = ThreadLocalRandom.current().nextLong(0, 61);
        return USER_PROFILE_NULL_CACHE_TTL.plusSeconds(jitterSeconds);
    }

    private boolean isRedisCircuitOpen() {
        return System.currentTimeMillis() < redisCircuitOpenUntil;
    }

    private void recordRedisSuccess() {
        redisFailureCounter.set(0);
    }

    private void recordRedisFailure(String stage, Exception e) {
        int failures = redisFailureCounter.incrementAndGet();
        log.error("Redis operation failed. stage={}, failures={}", stage, failures, e);
        if (failures >= REDIS_FAILURE_THRESHOLD) {
            redisCircuitOpenUntil = System.currentTimeMillis() + REDIS_CIRCUIT_BREAKER_MILLIS;
            log.warn("Redis circuit breaker opened. openUntil={}", redisCircuitOpenUntil);
        }
    }

    private void cacheNullUserProfile(String email) {
        String cacheKey = buildUserProfileCacheKey(email);
        if (isRedisCircuitOpen()) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(cacheKey, NULL_CACHE_VALUE, buildRandomizedNullCacheTtl());
            recordRedisSuccess();
            log.info("Redis null cache write. key={}", cacheKey);
        } catch (Exception e) {
            recordRedisFailure("cacheNullUserProfile", e);
        }
    }

    private void cacheUserProfile(UserProfile profile) {
        String cacheKey = buildUserProfileCacheKey(profile.getEmail());
        if (isRedisCircuitOpen()) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(profile);
            Duration ttl = buildRandomizedProfileTtl();
            stringRedisTemplate.opsForValue().set(cacheKey, json, ttl);
            recordRedisSuccess();
            log.info("Redis cache write. key={}, ttlMinutes={}", cacheKey, ttl.toMinutes());
        } catch (JsonProcessingException e) {
            log.error("Redis cache serialization failed. key={}", cacheKey, e);
        } catch (Exception e) {
            recordRedisFailure("cacheUserProfile", e);
        }
    }

    private UserProfile readUserProfileFromCache(String email) {
        String cacheKey = buildUserProfileCacheKey(email);
        if (isRedisCircuitOpen()) {
            return null;
        }
        try {
            String json = stringRedisTemplate.opsForValue().get(cacheKey);
            if (json == null || json.isBlank()) {
                log.info("Redis cache miss. key={}", cacheKey);
                return null;
            }
            if (NULL_CACHE_VALUE.equals(json)) {
                recordRedisSuccess();
                log.info("Redis null cache hit. key={}", cacheKey);
                return new UserProfile(null, NULL_CACHE_VALUE, email);
            }
            UserProfile profile = objectMapper.readValue(json, UserProfile.class);
            recordRedisSuccess();
            log.info("Redis cache hit. key={}", cacheKey);
            return profile;
        } catch (Exception e) {
            recordRedisFailure("readUserProfileFromCache", e);
            return null;
        }
    }

    private void evictUserProfileCache(String email) {
        String cacheKey = buildUserProfileCacheKey(email);
        if (isRedisCircuitOpen()) {
            return;
        }
        try {
            Boolean deleted = stringRedisTemplate.delete(cacheKey);
            recordRedisSuccess();
            log.info("Redis cache evict. key={}, deleted={}", cacheKey, Boolean.TRUE.equals(deleted));
        } catch (Exception e) {
            recordRedisFailure("evictUserProfileCache", e);
        }
    }

    private void scheduleDelayedCacheDelete(String email) {
        cacheDeleteExecutor.schedule(() -> evictUserProfileCache(email), DOUBLE_DELETE_DELAY_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void doubleDeleteUserProfileCache(String email) {
        evictUserProfileCache(email);
        scheduleDelayedCacheDelete(email);
    }

    private boolean tryLockUserProfile(String email) {
        if (isRedisCircuitOpen()) {
            return false;
        }
        try {
            Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(buildUserProfileLockKey(email), "1", LOCK_TTL);
            recordRedisSuccess();
            return Boolean.TRUE.equals(success);
        } catch (Exception e) {
            recordRedisFailure("tryLockUserProfile", e);
            return false;
        }
    }

    private void unlockUserProfile(String email) {
        if (isRedisCircuitOpen()) {
            return;
        }
        try {
            stringRedisTemplate.delete(buildUserProfileLockKey(email));
            recordRedisSuccess();
        } catch (Exception e) {
            recordRedisFailure("unlockUserProfile", e);
        }
    }

    private void waitForHotKeyRebuild() {
        try {
            TimeUnit.MILLISECONDS.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void addEmailToBloomFilter(String email) {
        executeRedisCommand("addEmailToBloomFilter", connection ->
            connection.execute("BF.ADD", bytes(USER_EMAIL_BLOOM_FILTER_KEY), bytes(email))
        );
    }

    private boolean mightContainEmail(String email) {
        Object result = executeRedisCommand("mightContainEmail", connection ->
            connection.execute("BF.EXISTS", bytes(USER_EMAIL_BLOOM_FILTER_KEY), bytes(email))
        );
        if (result instanceof Boolean exists) {
            return exists;
        }
        if (result instanceof byte[] bytes && bytes.length > 0) {
            return bytes[0] == 1;
        }
        return true;
    }

    private Result<UserProfile> degradeQueryResult() {
        return new Result<>(503, "系统繁忙，缓存重建已限流，请稍后重试", null);
    }

    private Result<UserProfile> queryUserProfileFromDatabase(String email) {
        User user;
        try {
            user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getEmail, email)
            );
        } catch (Exception e) {
            return userProfileDatabaseError(email, e);
        }

        if (user == null) {
            cacheNullUserProfile(email);
            return new Result<>(constant.badRequestCode, constant.msgEmailNotRegistered, null);
        }

        addEmailToBloomFilter(email);
        UserProfile profile = UserProfile.fromUser(user);
        cacheUserProfile(profile);
        return new Result<>(constant.successCode, "查询用户信息成功（已写入缓存）", profile);
    }
    
    public Result<Void> register(String email, String password) {
        log.info("Register start. email={}", email);
        /* 1. 检查邮箱是否已经注册 */
        // 使用 MP 的 selectOne 配合 LambdaQueryWrapper
        User existingUser;
        try {
            existingUser = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getEmail, email)
            );
        } catch (Exception e) {
            return registerDatabaseError(email, e);
        }

        if (existingUser != null) {
            log.warn("Register skipped because email already exists. email={}", email);
            return new Result<>(constant.badRequestCode, constant.msgEmailRegisted, null);
        }

        /* 2. 创建新用户并保存到“数据库” */
        User newUser = new User();
        newUser.setUsername(email);
        newUser.setEmail(email);
        newUser.setPassword(password);
        // 使用 MP 的 insert 方法插入数据库
        int rows;
        try {
            rows = userMapper.insert(newUser);
        } catch (Exception e) {
            return registerDatabaseError(email, e);
        }
        log.info("Register insert finished. email={}, rows={}, userId={}", email, rows, newUser.getId());

        if (rows != 1) {
            log.error("Register insert failed. email={}, rows={}", email, rows);
            return new Result<>(constant.badRequestCode, "注册失败，用户数据未写入数据库", null);
        }

        addEmailToBloomFilter(email);
        doubleDeleteUserProfileCache(email);
        return new Result<>(constant.successCode, constant.msgRegisterSuccess, null);
    }
    
    public Result<User> login(String email, String password) {
        /* 1. 从数据库获取用户 */
        User user;
        try {
            user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getEmail, email)
            );
        } catch (Exception e) {
            return loginDatabaseError(email, e);
        }
        
        /* 2. 检验邮箱是否注册 */
        if (user == null) {
            return new Result<>(constant.badRequestCode, constant.msgEmailNotRegistered, null);
        }
        
        /*3.检验密码是否正确 */
        if (!user.verifyPassword(password)) {
            return new Result<>(constant.badRequestCode, constant.msgPasswordError, null);
        }

        return new Result<>(constant.successCode, constant.msgLoginSuccess, user);
    }

    public Result<UserProfile> getUserProfile(String email) {
        if (email == null || email.isBlank()) {
            return new Result<>(constant.badRequestCode, "邮箱不能为空", null);
        }

        UserProfile cachedProfile = readUserProfileFromCache(email);
        if (cachedProfile != null) {
            if (email.equals(cachedProfile.getEmail()) && NULL_CACHE_VALUE.equals(cachedProfile.getUsername())) {
                return new Result<>(constant.badRequestCode, constant.msgEmailNotRegistered, null);
            }
            return new Result<>(constant.successCode, "查询用户信息成功（命中缓存）", cachedProfile);
        }

        if (bloomFilterReady && !mightContainEmail(email)) {
            cacheNullUserProfile(email);
            return new Result<>(constant.badRequestCode, constant.msgEmailNotRegistered, null);
        }

        if (!cacheRebuildSemaphore.tryAcquire()) {
            return degradeQueryResult();
        }

        try {
            boolean locked = tryLockUserProfile(email);
            if (locked) {
                try {
                    UserProfile secondCheckProfile = readUserProfileFromCache(email);
                    if (secondCheckProfile != null) {
                        if (email.equals(secondCheckProfile.getEmail()) && NULL_CACHE_VALUE.equals(secondCheckProfile.getUsername())) {
                            return new Result<>(constant.badRequestCode, constant.msgEmailNotRegistered, null);
                        }
                        return new Result<>(constant.successCode, "查询用户信息成功（命中缓存）", secondCheckProfile);
                    }
                    return queryUserProfileFromDatabase(email);
                } finally {
                    unlockUserProfile(email);
                }
            }

            waitForHotKeyRebuild();
            UserProfile retryProfile = readUserProfileFromCache(email);
            if (retryProfile != null) {
                if (email.equals(retryProfile.getEmail()) && NULL_CACHE_VALUE.equals(retryProfile.getUsername())) {
                    return new Result<>(constant.badRequestCode, constant.msgEmailNotRegistered, null);
                }
                return new Result<>(constant.successCode, "查询用户信息成功（命中缓存）", retryProfile);
            }
            return queryUserProfileFromDatabase(email);
        } finally {
            cacheRebuildSemaphore.release();
        }
    }

    public Result<UserProfile> createUserProfile(String email, String password, String username) {
        if (email == null || email.isBlank()) {
            return new Result<>(constant.badRequestCode, "邮箱不能为空", null);
        }
        if (password == null || password.isBlank()) {
            return new Result<>(constant.badRequestCode, "密码不能为空", null);
        }

        String finalUsername = (username == null || username.isBlank()) ? email : username;

        User existingUser;
        try {
            existingUser = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getEmail, email)
            );
        } catch (Exception e) {
            return new Result<>(500, "新增用户信息失败，数据库连接或配置异常", null);
        }

        if (existingUser != null) {
            return new Result<>(constant.badRequestCode, constant.msgEmailRegisted, null);
        }

        User newUser = new User();
        newUser.setEmail(email);
        newUser.setPassword(password);
        newUser.setUsername(finalUsername);

        int rows;
        try {
            rows = userMapper.insert(newUser);
        } catch (Exception e) {
            return new Result<>(500, "新增用户信息失败，数据库连接或配置异常", null);
        }

        if (rows != 1) {
            return new Result<>(constant.badRequestCode, "新增用户失败，用户数据未写入数据库", null);
        }

        addEmailToBloomFilter(email);
        UserProfile profile = UserProfile.fromUser(newUser);
        doubleDeleteUserProfileCache(email);
        return new Result<>(constant.successCode, "新增用户成功，数据库写入完成，缓存已双删", profile);
    }

    public Result<Void> updateUsername(String email, String username) {
        if (email == null || email.isBlank()) {
            return new Result<>(constant.badRequestCode, "邮箱不能为空", null);
        }
        if (username == null || username.isBlank()) {
            return new Result<>(constant.badRequestCode, "用户名不能为空", null);
        }

        evictUserProfileCache(email);
        int rows;
        try {
            rows = userMapper.update(
                null,
                new LambdaUpdateWrapper<User>()
                    .eq(User::getEmail, email)
                    .set(User::getUsername, username)
            );
        } catch (Exception e) {
            return updateUserDatabaseError(email, e);
        }

        if (rows != 1) {
            return new Result<>(constant.badRequestCode, constant.msgEmailNotRegistered, null);
        }

        doubleDeleteUserProfileCache(email);
        return new Result<>(constant.successCode, "用户名更新成功，缓存已双删", null);
    }

    public Result<Void> deleteUserProfile(String email) {
        if (email == null || email.isBlank()) {
            return new Result<>(constant.badRequestCode, "邮箱不能为空", null);
        }

        evictUserProfileCache(email);
        int rows;
        try {
            rows = userMapper.delete(
                new LambdaQueryWrapper<User>().eq(User::getEmail, email)
            );
        } catch (Exception e) {
            return deleteUserDatabaseError(email, e);
        }

        if (rows != 1) {
            return new Result<>(constant.badRequestCode, constant.msgEmailNotRegistered, null);
        }

        doubleDeleteUserProfileCache(email);
        return new Result<>(constant.successCode, "用户删除成功，缓存已双删", null);
    }
}
