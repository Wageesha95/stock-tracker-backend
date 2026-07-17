package com.personal.stocktracker.repository;

import com.personal.stocktracker.document.PdfUploadRecord;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface PdfUploadRecordRepository extends MongoRepository<PdfUploadRecord, String> {
    boolean existsByTradeDate(LocalDate tradeDate);
    List<PdfUploadRecord> findAllByOrderByUploadedAtDesc();

    boolean existsByUserIdAndTradeDate(String userId, LocalDate tradeDate);
    boolean existsByUserIdAndTradeDateAndBrokerId(String userId, LocalDate tradeDate, String brokerId);
    java.util.Optional<PdfUploadRecord> findByUserIdAndTradeDateAndBrokerId(String userId, LocalDate tradeDate, String brokerId);
    List<PdfUploadRecord> findByUserIdOrderByUploadedAtDesc(String userId);
}
