package com.personal.stocktracker;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class StockTrackerApplication {

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Colombo"));
        SpringApplication.run(StockTrackerApplication.class, args);
    }

}
