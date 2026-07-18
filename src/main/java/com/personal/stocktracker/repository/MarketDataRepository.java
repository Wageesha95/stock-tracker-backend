package com.personal.stocktracker.repository;

import com.personal.stocktracker.document.MarketData;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface MarketDataRepository extends MongoRepository<MarketData, String> {

    Optional<MarketData> findFirstByCompanyCodeOrderByTradeDateDesc(String companyCode);

    Optional<MarketData> findByCompanyCodeAndTradeDate(String companyCode, LocalDate tradeDate);

    // Most recent stored row for a company strictly before the given date — the "previous
    // market day" we already have data for, used to compute day-over-day change.
    Optional<MarketData> findFirstByCompanyCodeAndTradeDateBeforeOrderByTradeDateDesc(String companyCode, LocalDate tradeDate);

    List<MarketData> findByCompanyCodeOrderByTradeDateDesc(String companyCode);

    List<MarketData> findByTradeDate(LocalDate tradeDate);

    // Range deletes go through MongoTemplate (gte/lte) in MarketDataController — Spring Data
    // Mongo's derived "Between" is exclusive, and MongoTemplate keeps the range inclusive.
}
