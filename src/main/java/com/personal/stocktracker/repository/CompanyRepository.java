package com.personal.stocktracker.repository;

import com.personal.stocktracker.document.Company;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CompanyRepository extends MongoRepository<Company, String> {

    Optional<Company> findByCode(String code);

    boolean existsByCode(String code);
}
