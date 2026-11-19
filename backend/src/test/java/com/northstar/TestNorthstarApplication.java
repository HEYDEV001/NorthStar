package com.northstar;

import java.util.Arrays;
import java.util.stream.Stream;
import org.springframework.boot.SpringApplication;

/**
 * Runs the app against a throwaway Supabase Postgres container: ./mvnw spring-boot:test-run. It is a local run, not a
 * test, so it reads ../.env.local as spring-boot:run does (for example NORTHSTAR_EMAIL_PROVIDER=mailpit): the test
 * classpath turns that import off for tests, and the argument below turns it back on here.
 */
public class TestNorthstarApplication {

    static final String READ_LOCAL_ENV = "--northstar.local-env.import=optional:file:../.env.local[.properties]";

    public static void main(String[] args) {
        SpringApplication.from(NorthstarApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(Stream.concat(Stream.of(READ_LOCAL_ENV), Arrays.stream(args))
                        .toArray(String[]::new));
    }
}
