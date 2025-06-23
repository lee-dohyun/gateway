package com.dh.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SpringBootApplication
public class GatewayApplication {

	private static final Logger logger = LoggerFactory.getLogger(GatewayApplication.class);

	public static void main(String[] args) {
		logger.info("GatewayApplication 시작됨");
		SpringApplication.run(GatewayApplication.class, args);
		logger.info("SpringApplication.run() 호출 완료");
	}

}
