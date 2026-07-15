package com.personal.stocktracker.repository;

import com.personal.stocktracker.document.ShareSplit;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ShareSplitRepository extends MongoRepository<ShareSplit, String> {
    List<ShareSplit> findAllByOrderByDateDesc();
}
