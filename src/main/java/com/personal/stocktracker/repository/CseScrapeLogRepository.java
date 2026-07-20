package com.personal.stocktracker.repository;

import com.personal.stocktracker.document.CseScrapeLog;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface CseScrapeLogRepository extends MongoRepository<CseScrapeLog, String> {

    // GreaterThanEqual (not Between) — Mongo's Between is exclusive of the bounds.
    List<CseScrapeLog> findByStartedAtGreaterThanEqualOrderByStartedAtDesc(LocalDateTime from);
}
