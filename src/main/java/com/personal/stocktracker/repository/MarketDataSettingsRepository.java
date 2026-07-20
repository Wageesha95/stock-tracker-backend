package com.personal.stocktracker.repository;

import com.personal.stocktracker.document.MarketDataSettings;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MarketDataSettingsRepository extends MongoRepository<MarketDataSettings, String> {
}
