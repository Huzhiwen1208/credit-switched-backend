package org.credit.biz.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Data
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
@Builder
@Entity
@Table(name = "users",
    indexes = {
        @Index(name = "idx_dept_id", columnList = "deptId"),
        @Index(name = "idx_enabled", columnList = "enabled")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uniq_email", columnNames = "email"),
        @UniqueConstraint(name = "uniq_username", columnNames = "username")
    }
)
public class SysUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "dept_id")
    private Long deptId;

    @Column(name = "username", length = 180)
    private String username;

    @Column(name = "nick_name", length = 255)
    private String nickName;

    @Column(name = "gender", length = 2)
    private String gender;

    @Column(name = "phone", length = 255)
    private String phone;

    @Column(name = "email", length = 180, unique = true)
    private String email;

    @Column(name = "avatar_name", length = 255)
    private String avatarName;

    @Column(name = "avatar_path", length = 255)
    private String avatarPath;

    @Column(name = "password", length = 255)
    private String password;

    @Column(name = "is_admin")
    private Boolean isAdmin = false; // bit(1) 映射为 Boolean

    @Column(name = "enabled")
    private Boolean enabled; // bit(1)

    @Column(name = "create_by", length = 255)
    private String createBy;

    @Column(name = "update_by", length = 255)
    private String updateBy;

    @Column(name = "pwd_reset_time")
    private LocalDateTime pwdResetTime;

    @CreatedDate
    @Column(name = "create_time", updatable = false)
    private LocalDateTime createTime;

    @LastModifiedDate
    @Column(name = "update_time")
    private LocalDateTime updateTime;
}