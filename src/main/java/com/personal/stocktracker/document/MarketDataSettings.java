package com.personal.stocktracker.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Single-row admin settings for market-data ingestion. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "market_data_settings")
public class MarketDataSettings {

    public static final String SINGLETON_ID = "market-data-settings";

    @Id
    private String id;

    // When true, the TradingView web-scraper may overwrite rows sourced from the CSE API.
    // Null/false = protect CSE rows (default): the scraper skips them.
    private Boolean scraperOverwriteCse;
}
