package com.personal.stocktracker.repository;

import com.personal.stocktracker.document.Ipo;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface IpoRepository extends MongoRepository<Ipo, String> {
    List<Ipo> findByUserIdOrderByDateDesc(String userId);
}
