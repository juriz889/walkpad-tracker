package com.walkingpad;

import com.walkingpad.config.WalkingPadProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(WalkingPadProperties.class)
public class WalkingpadApplication {

    public static void main(String[] args) {
        SpringApplication.run(WalkingpadApplication.class, args);
    }
}
