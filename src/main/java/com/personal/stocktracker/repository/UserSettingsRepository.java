package com.personal.stocktracker.repository;

import com.personal.stocktracker.document.UserSettings;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserSettingsRepository extends MongoRepository<UserSettings, String> {

    Optional<UserSettings> findByUserId(String userId);
}
