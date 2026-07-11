package com.example.postsearch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import com.example.platform.messaging.support.InboxMessage;
import com.example.platform.messaging.support.InboxMessageRepository;
import com.example.postsearch.model.SearchDocument;
import com.example.postsearch.repository.SearchDocumentRepository;

@SpringBootApplication
@EntityScan(basePackageClasses = {SearchDocument.class, InboxMessage.class})
@EnableJpaRepositories(basePackageClasses = {SearchDocumentRepository.class, InboxMessageRepository.class})
public class PostSearchApplication {
    public static void main(String[] args) {
        SpringApplication.run(PostSearchApplication.class, args);
    }
}
