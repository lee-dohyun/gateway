package com.dh.gateway.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

@RestController
public class CircuitBreakerFallbackController {

    @RequestMapping("/fallback")
    public Mono<ResponseEntity<Map<String, Object>>> fallback() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", HttpStatus.SERVICE_UNAVAILABLE.value());
        response.put("error", HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase());
        response.put("message", "일시적인 장애가 발생했습니다. (Service Unavailable)");
        
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response));
    }
}
