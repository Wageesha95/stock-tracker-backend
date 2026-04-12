package com.personal.stocktracker.repository;

import com.personal.stocktracker.document.DividendFinancial;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DividendFinancialRepository extends MongoRepository<DividendFinancial, String> {

    List<DividendFinancial> findByCompanyCodeOrderByYearDesc(String companyCode);

    Optional<DividendFinancial> findByCompanyCodeAndYear(String companyCode, int year);
}
