package com.example.kafkaviz;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class KafkaVisualizerApplication {

    public static void main(String[] args) {
        SpringApplication.run(KafkaVisualizerApplication.class, args);
    }
}