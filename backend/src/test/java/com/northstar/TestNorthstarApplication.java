package com.northstar;

import org.springframework.boot.SpringApplication;

/** Runs the app against a throwaway Supabase Postgres container: ./mvnw spring-boot:test-run */
public class TestNorthstarApplication {

    public static void main(String[] args) {
        SpringApplication.from(NorthstarApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
