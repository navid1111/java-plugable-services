package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.example.demo.model.User;
import com.example.demo.repository.UserRepository;
import com.example.platform.messaging.support.OutboxMessage;
import com.example.platform.messaging.support.OutboxMessageRepository;

@SpringBootApplication
@EnableScheduling
@EntityScan(basePackageClasses = {User.class, OutboxMessage.class})
@EnableJpaRepositories(basePackageClasses = {UserRepository.class, OutboxMessageRepository.class})
public class DemoApplication {

	public static void main(String[] args) {
		SpringApplication.run(DemoApplication.class, args);
	}

}
