package org.credit.biz.model;


import jakarta.persistence.*;
import java.io.Serializable;

@Entity
@Table(name = "user_table")
public class UserA implements Serializable { // 必须实现序列化
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    private String name;
    private String school;
    private String company;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSchool() { return school; }
    public void setSchool(String school) { this.school = school; }
    public String getCompany() { return company; }
    public void setCompany(String company) { this.company = company; }
}

