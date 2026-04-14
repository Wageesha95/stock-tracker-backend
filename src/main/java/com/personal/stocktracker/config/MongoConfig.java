package com.personal.stocktracker.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;

import jakarta.annotation.PostConstruct;

/**
 * Allows dots in MongoDB map keys (e.g. company codes like DIAL.N) by
 * configuring a replacement character on the MappingMongoConverter.
 */
@Configuration
public class MongoConfig {

    @Autowired
    private MappingMongoConverter mappingMongoConverter;

    @PostConstruct
    public void setMapKeyDotReplacement() {
        mappingMongoConverter.setMapKeyDotReplacement("_");
    }
}
