package com.example.comment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import com.example.comment.model.Comment;
import com.example.comment.repository.CommentRepository;
import com.example.platform.messaging.support.TargetProjection;
import com.example.platform.messaging.support.TargetProjectionRepository;

@SpringBootApplication
@EntityScan(basePackageClasses = {Comment.class, TargetProjection.class})
@EnableJpaRepositories(basePackageClasses = {CommentRepository.class, TargetProjectionRepository.class})
public class CommentApplication {
    public static void main(String[] args) {
        SpringApplication.run(CommentApplication.class, args);
    }
}
