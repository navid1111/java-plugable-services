package com.example.tweeter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.example.platform.messaging.support.OutboxMessage;
import com.example.platform.messaging.support.OutboxMessageRepository;
import com.example.tweeter.model.Post;
import com.example.tweeter.repository.PostRepository;

@SpringBootApplication
@EntityScan(basePackageClasses = {Post.class, OutboxMessage.class})
@EnableJpaRepositories(basePackageClasses = {PostRepository.class, OutboxMessageRepository.class})
public class TweeterApplication {
    public static void main(String[] args) {
        SpringApplication.run(TweeterApplication.class, args);
    }
}
