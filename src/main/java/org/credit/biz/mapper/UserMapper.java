package org.credit.biz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.credit.biz.model.User;
import org.apache.ibatis.annotations.Mapper;

// @Mapper 注解告诉 Spring 这是一个 MyBatis 的 Mapper 接口
@Mapper
public interface UserMapper extends BaseMapper<User> {
    // 继承 BaseMapper 后，自动拥有了 insert, selectOne, update, delete 等方法
    // 不需要写任何代码！
}