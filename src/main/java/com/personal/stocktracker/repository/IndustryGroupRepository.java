package com.personal.stocktracker.repository;

import com.personal.stocktracker.document.IndustryGroup;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface IndustryGroupRepository extends MongoRepository<IndustryGroup, String> {
    Optional<IndustryGroup> findByName(String name);
    boolean existsByName(String name);
}
