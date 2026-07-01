package io.audienceflow.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class AudienceFlowApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(AudienceFlowApiApplication.class, args);
    }
}
