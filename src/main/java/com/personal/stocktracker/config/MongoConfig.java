package com.personal.stocktracker.config;

import com.personal.stocktracker.document.MarketData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;

import jakarta.annotation.PostConstruct;

/**
 * Allows dots in MongoDB map keys (e.g. company codes like DIAL.N) by
 * configuring a replacement character on the MappingMongoConverter, and
 * ensures the market_data indexes the dashboard aggregations rely on exist.
 */
@Slf4j
@Configuration
public class MongoConfig {

    @Autowired
    private MappingMongoConverter mappingMongoConverter;

    @Autowired
    private MongoTemplate mongoTemplate;

    @PostConstruct
    public void setMapKeyDotReplacement() {
        mappingMongoConverter.setMapKeyDotReplacement("_");
    }

    /**
     * Create the market_data indexes programmatically rather than via
     * spring.data.mongodb.auto-index-creation, so a single failing index (e.g. the unique
     * compound index hitting unexpected duplicate data) only logs and is skipped instead of
     * aborting application startup. ensureIndex is idempotent — a no-op once the index exists.
     *
     * <p>Without these, every market_data aggregation does a full collection scan and an
     * unindexed in-memory sort, which exceeds MongoDB's 32MB sort cap (error 292) and is slow.
     */
    @PostConstruct
    public void ensureMarketDataIndexes() {
        IndexOperations ops = mongoTemplate.indexOps(MarketData.class);

        // Serves the date-range $match and tradeDate sort in the dashboard aggregations
        // (sparklines, ytd, year-low), findByTradeDate, and the delete-by-date-range queries.
        // Also lets the "latest per company" sort stream from an index instead of doing a
        // 32MB-capped in-memory sort.
        ensure(ops, new Index().on("tradeDate", Sort.Direction.ASC).named("tradeDate_idx"), "tradeDate_idx");

        // Serves every per-company lookup (findByCompanyCode*, findByCompanyCodeAndTradeDate)
        // and getLatestPerCompany's {companyCode, tradeDate} sort, and enforces one row per
        // company per trade date.
        ensure(ops, new Index().on("companyCode", Sort.Direction.ASC)
                .on("tradeDate", Sort.Direction.ASC).unique().named("company_date_idx"), "company_date_idx");
    }

    private void ensure(IndexOperations ops, Index index, String name) {
        try {
            ops.ensureIndex(index);
            log.info("Ensured market_data index '{}'", name);
        } catch (Exception e) {
            log.error("Could not create market_data index '{}' — continuing without it. Cause: {}",
                    name, e.getMessage());
        }
    }
}
