package com.ai.orange;

import org.springframework.boot.SpringApplication;

public class TestOrangeApplication {

    public static void main(String[] args) {
        SpringApplication.from(OrangeApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
