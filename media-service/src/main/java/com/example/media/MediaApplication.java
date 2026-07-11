package com.example.media;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import com.example.media.model.MediaAsset;
import com.example.media.repository.MediaAssetRepository;
import com.example.platform.messaging.support.TargetProjection;
import com.example.platform.messaging.support.TargetProjectionRepository;

@SpringBootApplication
@EntityScan(basePackageClasses = {MediaAsset.class, TargetProjection.class})
@EnableJpaRepositories(basePackageClasses = {MediaAssetRepository.class, TargetProjectionRepository.class})
public class MediaApplication {
    public static void main(String[] args) {
        SpringApplication.run(MediaApplication.class, args);
    }
}
