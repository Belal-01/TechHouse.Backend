package com.techhouse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TechHouseApplication {

	public static void main(String[] args) {
		SpringApplication.run(TechHouseApplication.class, args);
	}

}
