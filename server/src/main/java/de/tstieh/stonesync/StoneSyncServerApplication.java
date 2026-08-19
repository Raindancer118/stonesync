package de.tstieh.stonesync;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class StoneSyncServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(StoneSyncServerApplication.class, args);
    }
}
