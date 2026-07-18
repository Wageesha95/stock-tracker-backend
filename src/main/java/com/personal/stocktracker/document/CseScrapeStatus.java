package com.personal.stocktracker.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/** Single-row record of the last CSE market-data fetch, for the admin panel status display. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "cse_scrape_status")
public class CseScrapeStatus {

    public static final String SINGLETON_ID = "cse-market-data";

    @Id
    private String id;

    private LocalDateTime lastRunAt;   // server-stamped time of the last run
    private String tradeDate;          // market day the run aligned to
    private int total;
    private int saved;
    private int failed;
    private String status;             // success | partial | failed
}
