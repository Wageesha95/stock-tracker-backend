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

    List<MarketData> findByCompanyCodeOrderByTradeDateDesc(String companyCode);

    List<MarketData> findByTradeDate(LocalDate tradeDate);

    long deleteByTradeDateBefore(LocalDate date);

    // NOTE: Spring Data MongoDB's "Between" is EXCLUSIVE on both ends, which silently skips
    // records on the boundary dates (e.g. the newest day). Use the inclusive variant below.
    long deleteByTradeDateBetween(LocalDate from, LocalDate to);

    long deleteByTradeDateGreaterThanEqualAndTradeDateLessThanEqual(LocalDate from, LocalDate to);

    long deleteByTradeDateGreaterThanEqual(LocalDate from);

    long deleteByTradeDateLessThanEqual(LocalDate to);
}
