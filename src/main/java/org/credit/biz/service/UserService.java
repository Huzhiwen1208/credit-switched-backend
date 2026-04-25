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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;


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
    private static final String USER_PROFILE_CACHE_PREFIX = "user:profile:";

    private String buildUserProfileCacheKey(String email) {
        return USER_PROFILE_CACHE_PREFIX + email;
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

    private void cacheUserProfile(UserProfile profile) {
        String cacheKey = buildUserProfileCacheKey(profile.getEmail());
        try {
            String json = objectMapper.writeValueAsString(profile);
            stringRedisTemplate.opsForValue().set(cacheKey, json, USER_PROFILE_CACHE_TTL);
            log.info("Redis cache write. key={}, ttlMinutes={}", cacheKey, USER_PROFILE_CACHE_TTL.toMinutes());
        } catch (JsonProcessingException e) {
            log.error("Redis cache serialization failed. key={}", cacheKey, e);
        } catch (Exception e) {
            log.error("Redis cache write failed. key={}", cacheKey, e);
        }
    }

    private UserProfile readUserProfileFromCache(String email) {
        String cacheKey = buildUserProfileCacheKey(email);
        try {
            String json = stringRedisTemplate.opsForValue().get(cacheKey);
            if (json == null || json.isBlank()) {
                log.info("Redis cache miss. key={}", cacheKey);
                return null;
            }
            UserProfile profile = objectMapper.readValue(json, UserProfile.class);
            log.info("Redis cache hit. key={}", cacheKey);
            return profile;
        } catch (Exception e) {
            log.error("Redis cache read failed. key={}", cacheKey, e);
            return null;
        }
    }

    private void evictUserProfileCache(String email) {
        String cacheKey = buildUserProfileCacheKey(email);
        try {
            Boolean deleted = stringRedisTemplate.delete(cacheKey);
            log.info("Redis cache evict. key={}, deleted={}", cacheKey, Boolean.TRUE.equals(deleted));
        } catch (Exception e) {
            log.error("Redis cache evict failed. key={}", cacheKey, e);
        }
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
            return new Result<>(constant.successCode, "查询用户信息成功（命中缓存）", cachedProfile);
        }

        User user;
        try {
            user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getEmail, email)
            );
        } catch (Exception e) {
            return userProfileDatabaseError(email, e);
        }

        if (user == null) {
            return new Result<>(constant.badRequestCode, constant.msgEmailNotRegistered, null);
        }

        UserProfile profile = UserProfile.fromUser(user);
        cacheUserProfile(profile);
        return new Result<>(constant.successCode, "查询用户信息成功（已写入缓存）", profile);
    }

    public Result<Void> updateUsername(String email, String username) {
        if (email == null || email.isBlank()) {
            return new Result<>(constant.badRequestCode, "邮箱不能为空", null);
        }
        if (username == null || username.isBlank()) {
            return new Result<>(constant.badRequestCode, "用户名不能为空", null);
        }

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

        evictUserProfileCache(email);
        return new Result<>(constant.successCode, "用户名更新成功，缓存已删除", null);
    }
}
