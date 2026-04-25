package org.credit.biz;

import org.mybatis.spring.annotation.MapperScan; // 1. 引入 MapperScan 注解
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("org.credit.biz.mapper") // 2. 扫描 Mapper 接口所在的包

public class CreditSwitchApplication {

	public static void main(String[] args) {
		SpringApplication.run(CreditSwitchApplication.class, args);
	}

}
