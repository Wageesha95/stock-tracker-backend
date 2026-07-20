package com.personal.stocktracker.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/** One row per CSE market-data fetch run, for the admin panel execution log. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "cse_scrape_log")
public class CseScrapeLog {

    @Id
    private String id;

    @Indexed
    private LocalDateTime startedAt;   // when the run began
    private LocalDateTime endedAt;     // when the run finished (server-stamped)
    private String tradeDate;          // market day the run aligned to
    private int total;
    private int saved;
    private int failed;
    private String status;             // success | partial | failed
    private String trigger;            // auto | manual
}
