package org.credit.biz;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class CreditSwitchApplication {

	public static void main(String[] args) {
		SpringApplication.run(CreditSwitchApplication.class, args);
	}

}
