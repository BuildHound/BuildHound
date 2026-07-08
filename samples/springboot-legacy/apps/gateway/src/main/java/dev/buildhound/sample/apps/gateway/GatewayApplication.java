package dev.buildhound.sample.apps.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Spring Boot entry point for :apps:gateway. Fans in over the service DAG (see build.gradle deps). */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
        // Touch a wired module so the dependency edge is a real compile dependency.
        System.out.println(dev.buildhound.sample.services.orders.web.Module.name());
    }
}
