package com.personal.stocktracker.repository;

import com.personal.stocktracker.document.Note;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NoteRepository extends MongoRepository<Note, String> {

    List<Note> findByUserIdOrderByUpdatedAtDesc(String userId);

    List<Note> findByUserIdAndCompanyCodeOrderByUpdatedAtDesc(String userId, String companyCode);
}
