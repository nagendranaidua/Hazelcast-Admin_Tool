package com.genius.hz.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableGlobalMethodSecurity(prePostEnabled = true)
@EnableAsync
@EnableScheduling
public class HzAdminApplication {
    public static void main(String[] args) {
        SpringApplication.run(HzAdminApplication.class, args);
    }
}
