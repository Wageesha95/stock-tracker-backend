package com.personal.stocktracker.repository;

import com.personal.stocktracker.document.LoginHistory;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface LoginHistoryRepository extends MongoRepository<LoginHistory, String> {
    List<LoginHistory> findAllByOrderByTimestampDesc();
    void deleteByTimestampBefore(LocalDateTime cutoff);
}
