package org.credit.biz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper; // 引入 MP 的条件构造器
import org.credit.biz.common.Result;
import org.credit.biz.constant.AuthHandlerConstant;
import org.credit.biz.mapper.UserMapper; // 1. 引入 Mapper
import org.credit.biz.model.User;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;


@Service
@Slf4j
@RequiredArgsConstructor  // Lombok 会自动生成 final 字段的构造函数
public class UserService {

    // 1. 删除模拟数据库，注入 UserMapper (Spring Boot 会自动把 Mapper 的代理对象注入进来)
    private final UserMapper userMapper;
    
    private final AuthHandlerConstant constant;

    private Result<User> loginDatabaseError(String email, Exception e) {
        log.error("Login database access failed. email={}", email, e);
        return new Result<>(500, "登录失败，数据库连接或配置异常", null);
    }

    private Result<Void> registerDatabaseError(String email, Exception e) {
        log.error("Register database access failed. email={}", email, e);
        return new Result<>(500, "注册失败，数据库连接或配置异常", null);
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
}
