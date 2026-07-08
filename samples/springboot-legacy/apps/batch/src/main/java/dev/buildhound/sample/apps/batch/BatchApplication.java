package dev.buildhound.sample.apps.batch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Spring Boot entry point for :apps:batch. Fans in over the service DAG (see build.gradle deps). */
@SpringBootApplication
public class BatchApplication {

    public static void main(String[] args) {
        SpringApplication.run(BatchApplication.class, args);
        // Touch a wired module so the dependency edge is a real compile dependency.
        System.out.println(dev.buildhound.sample.services.orders.domain.Module.name());
    }
}
