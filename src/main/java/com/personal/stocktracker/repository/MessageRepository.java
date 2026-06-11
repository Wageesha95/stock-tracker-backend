package com.personal.stocktracker.repository;

import com.personal.stocktracker.document.Message;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MessageRepository extends MongoRepository<Message, String> {

    List<Message> findAllByOrderByCreatedAtDesc();

    List<Message> findByFromUsernameOrderByCreatedAtDesc(String fromUsername);

    long countByReadFalse();
}
