package com.personal.stocktracker.repository;

import com.personal.stocktracker.document.Rights;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RightsRepository extends MongoRepository<Rights, String> {

    List<Rights> findByUserIdOrderByDateDesc(String userId);

    List<Rights> findByUserIdAndCompanyCodeOrderByDateDesc(String userId, String companyCode);
}
