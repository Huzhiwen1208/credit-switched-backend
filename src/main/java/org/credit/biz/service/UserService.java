package org.credit.biz.service;

import org.credit.biz.common.Result;
import org.credit.biz.constant.AuthHandlerConstant;
import org.credit.biz.model.User;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class UserService {

    /* ============================================================
    【模拟数据库】
    这里的 static Map 就像是一个在内存里的“记事本”。
    程序启动时，它存在；程序关闭，数据就丢了。
    ============================================================*/
    private static final Map<String, User> MOCK_DB = new ConcurrentHashMap<>();
    private final AuthHandlerConstant constant;

    
    public Result<Void> register(String email, String password) {
        /* 1. 检查邮箱是否已经注册 */
        if (MOCK_DB.containsKey(email)) {
            return new Result<>(constant.badRequestCode, constant.msgEmailRegisted, null);
        }
        /* 2. 创建新用户并保存到“数据库” */
        User newUser = new User(email, password);
        newUser.saveToDb(MOCK_DB);

        return new Result<>(constant.successCode, constant.msgRegisterSuccess, null);
    }
    
    public Result<User> login(String email, String password) {
        /*1.获取模拟数据库里与登录邮箱对应的User对象 */
        User user = MOCK_DB.get(email);
        
        /*2.检验邮箱是否注册 */
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