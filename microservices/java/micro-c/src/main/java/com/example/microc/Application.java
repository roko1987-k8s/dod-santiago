package com.example.microc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @GetMapping("/")
    public String home() {
        return """
                {
                  "service": "micro-c",
                  "language": "Java",
                  "framework": "Spring Boot"
                }
                """;
    }

    @GetMapping("/health")
    public String health() {
        return "{\"status\":\"UP\"}";
    }
}
