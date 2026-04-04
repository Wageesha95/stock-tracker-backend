package com.personal.stocktracker.repository;

import com.personal.stocktracker.document.StockPrice;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StockPriceRepository extends MongoRepository<StockPrice, String> {

    List<StockPrice> findByCompanyCodeOrderByDateDesc(String companyCode);

    Optional<StockPrice> findFirstByCompanyCodeOrderByDateDescCreatedAtDesc(String companyCode);
}
