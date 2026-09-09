package com.example.microa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@SpringBootApplication
@RestController
public class Application {

    private final HttpClient client = HttpClient.newHttpClient();

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @GetMapping("/")
    public String home() {
        String version = System.getenv().getOrDefault("VERSION", "unknown");
    
        return """
                {
                  "service": "micro-a",
                  "language": "Java",
                  "framework": "Spring Boot",
                  "version": "%s"
                }
                """.formatted(version);
    }

    @GetMapping("/health")
    public String health() {
        return "{\"status\":\"UP\"}";
    }

    @GetMapping("/call-b")
    public String callB() throws Exception {

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://micro-b:8443/"))
                .GET()
                .build();

        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());

        return response.body();
    }
}
