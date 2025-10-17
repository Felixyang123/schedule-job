package com.wly.job.server;

import com.wly.config.core.annotation.EnableRegistry;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.wly.job.server.dao.mapper")
@EnableRegistry
public class JobAdminApplication {

	public static void main(String[] args) {
		SpringApplication.run(JobAdminApplication.class, args);
	}

}
