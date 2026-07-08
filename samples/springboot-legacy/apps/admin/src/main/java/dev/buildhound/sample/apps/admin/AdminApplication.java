package dev.buildhound.sample.apps.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Spring Boot entry point for :apps:admin. Fans in over the service DAG (see build.gradle deps). */
@SpringBootApplication
public class AdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminApplication.class, args);
        // Touch a wired module so the dependency edge is a real compile dependency.
        System.out.println(dev.buildhound.sample.services.users.web.Module.name());
    }
}
