package com.personal.stocktracker.repository;

import com.personal.stocktracker.document.User;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface UserRepository extends MongoRepository<User, String> {

    Optional<User> findByUsername(String username);

    long countByCreatedAtBetween(LocalDateTime start, LocalDateTime end);
}
