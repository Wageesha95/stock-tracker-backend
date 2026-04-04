package com.personal.stocktracker.repository;

import com.personal.stocktracker.document.Transaction;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TransactionRepository extends MongoRepository<Transaction, String> {

    List<Transaction> findAllByOrderByDateDesc();

    List<Transaction> findByCompanyCodeOrderByDateDesc(String companyCode);

    List<Transaction> findByUserIdOrderByDateDesc(String userId);

    List<Transaction> findByUserIdAndCompanyCodeOrderByDateDesc(String userId, String companyCode);
}
