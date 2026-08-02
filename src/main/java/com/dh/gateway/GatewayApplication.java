package com.dh.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dh.gateway.security.GatewaySecurityProperties;

@SpringBootApplication
@EnableConfigurationProperties(GatewaySecurityProperties.class)
public class GatewayApplication {

	private static final Logger logger = LoggerFactory.getLogger(GatewayApplication.class);

	public static void main(String[] args) {
		logger.info("GatewayApplication 시작됨");
		SpringApplication.run(GatewayApplication.class, args);
		logger.info("SpringApplication.run() 호출 완료");
	}

}
