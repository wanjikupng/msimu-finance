package com.tracom.lipamdogo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LipaMdogoApplication {

    public static void main(String[] args) {
        SpringApplication.run(LipaMdogoApplication.class, args);
    }
}
