package com.personal.stocktracker.repository;

import com.personal.stocktracker.document.CseScrapeStatus;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CseScrapeStatusRepository extends MongoRepository<CseScrapeStatus, String> {
}
