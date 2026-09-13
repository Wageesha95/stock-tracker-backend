package com.personal.stocktracker.repository;

import com.personal.stocktracker.document.ShareTransfer;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ShareTransferRepository extends MongoRepository<ShareTransfer, String> {
    List<ShareTransfer> findByUserIdOrderByDateDesc(String userId);
}
