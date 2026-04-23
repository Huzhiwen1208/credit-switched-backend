package org.credit.biz.service;

import org.credit.biz.model.UserA;
import org.credit.biz.Respository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class UserAService {
    @Autowired
    private UserRepository userRepository;
    
    @Cacheable(value = "user", key = "#id")
    public UserA getUserById(Integer id) {
        System.out.println("--- Redis 没命中，正在从 MySQL 数据库查询 id 为 " + id + " 的用户 ---");
        return userRepository.findById(id).orElse(null);
    }
}
