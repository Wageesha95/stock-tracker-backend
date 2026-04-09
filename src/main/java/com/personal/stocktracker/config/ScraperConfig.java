package com.personal.stocktracker.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "app.scraper")
public class ScraperConfig {
    private String baseUrl;
    private String urlSuffix;
    private long delayMs = 6000;
    private boolean headless = true;
    private int pageLoadTimeoutSec = 30;
    private int elementWaitTimeoutSec = 15;
}
