package net.osparty.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class OsrsPartyApiApplication {
	public static void main(String[] args) {
		SpringApplication.run(OsrsPartyApiApplication.class, args);
	}
}
