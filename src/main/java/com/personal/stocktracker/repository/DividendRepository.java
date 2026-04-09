package com.personal.stocktracker.repository;

import com.personal.stocktracker.document.Dividend;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DividendRepository extends MongoRepository<Dividend, String> {

    List<Dividend> findByCompanyCodeOrderByDateDesc(String companyCode);

    List<Dividend> findAllByOrderByDateDesc();

    List<Dividend> findByUserIdOrderByDateDesc(String userId);

    List<Dividend> findByUserIdAndCompanyCodeOrderByDateDesc(String userId, String companyCode);

    boolean existsByUserIdAndCompanyCodeAndXdDate(String userId, String companyCode, java.time.LocalDate xdDate);
}
