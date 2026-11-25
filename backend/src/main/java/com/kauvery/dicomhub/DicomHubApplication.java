package com.kauvery.dicomhub;

import com.kauvery.dicomhub.config.HubProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(HubProperties.class)
public class DicomHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(DicomHubApplication.class, args);
    }
}
