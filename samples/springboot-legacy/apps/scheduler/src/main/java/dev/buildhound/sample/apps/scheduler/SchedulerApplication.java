package dev.buildhound.sample.apps.scheduler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Spring Boot entry point for :apps:scheduler. Fans in over the service DAG (see build.gradle deps). */
@SpringBootApplication
public class SchedulerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SchedulerApplication.class, args);
        // Touch a wired module so the dependency edge is a real compile dependency.
        System.out.println(dev.buildhound.sample.services.users.domain.Module.name());
    }
}
