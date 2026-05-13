package org.credit.biz.model;
// 1. 引入 MyBatis-Plus 的注解
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField; // 用于忽略不存在的字段

import lombok.Data;
import java.time.LocalDateTime;
import java.util.Map;

@Data // Lombok 自动生成 getter/setter
@TableName("users") // 【重要】这里填你数据库里的真实表名，如果是 "user" 请改为 "user"
public class User {

    // --- 新增：数据库主键 ---
    @TableId(type = IdType.AUTO) // 告诉 MP 这是主键，且使用数据库自增策略
    private Long id;

    // --- 新增：创建时间（先忽略） ---
    @TableField(exist = false)
    private LocalDateTime createTime;

    private String username;

    private String email;
    
    @TableField("password_hash")
    private String password;

    public User() {
    }

    public User(String email, String password) {
        this.username = email;
        this.email = email;
        this.password = password;
    }

    public  void saveToDb(Map<String, User>database){
        database.put(this.email, this);
    }

    public boolean verifyPassword(String inputpassword) {
        return this.password.equals(inputpassword);
    }

}
