package com.personal.stocktracker.repository;

import com.personal.stocktracker.document.DividendPayout;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface DividendPayoutRepository extends MongoRepository<DividendPayout, String> {

    List<DividendPayout> findByCompanyCodeOrderByExDividendDateDesc(String companyCode);

    Optional<DividendPayout> findByCompanyCodeAndExDividendDate(String companyCode, LocalDate exDividendDate);

    boolean existsByCompanyCodeAndExDividendDate(String companyCode, LocalDate exDividendDate);
}
