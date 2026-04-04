package com.personal.stocktracker.repository;

import com.personal.stocktracker.document.Watchlist;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WatchlistRepository extends MongoRepository<Watchlist, String> {

    List<Watchlist> findByUserIdOrderByCreatedAtAsc(String userId);
}
