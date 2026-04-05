package com.personal.stocktracker.repository;

import com.personal.stocktracker.document.Broker;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BrokerRepository extends MongoRepository<Broker, String> {
    List<Broker> findAllByOrderByNameAsc();
    boolean existsByName(String name);
}
