package com.personal.stocktracker.controller;

import com.personal.stocktracker.document.Company;
import com.personal.stocktracker.document.Rights;
import com.personal.stocktracker.document.Transaction;
import com.personal.stocktracker.document.TransactionType;
import com.personal.stocktracker.repository.CompanyRepository;
import com.personal.stocktracker.repository.RightsRepository;
import com.personal.stocktracker.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/rights")
@RequiredArgsConstructor
public class RightsController {

    private final RightsRepository rightsRepository;
    private final TransactionRepository transactionRepository;
    private final CompanyRepository companyRepository;

    private String currentUsername() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    @GetMapping
    public ResponseEntity<List<Rights>> getAll() {
        return ResponseEntity.ok(rightsRepository.findByUserIdOrderByDateDesc(currentUsername()));
    }

    @PostMapping
    public ResponseEntity<Rights> create(@RequestBody Map<String, Object> body) {
        String username = currentUsername();
        String companyCode = ((String) body.get("companyCode")).toUpperCase();
        String dateStr = (String) body.get("date");
        int count = ((Number) body.get("count")).intValue();
        BigDecimal price = new BigDecimal(body.get("price").toString());

        // Auto-create company if needed
        if (!companyRepository.existsByCode(companyCode)) {
            companyRepository.save(Company.builder()
                    .code(companyCode).name(companyCode)
                    .createdAt(LocalDateTime.now()).build());
        }

        // Create linked transaction (RIGHTS type, zero commission)
        Transaction transaction = Transaction.builder()
                .userId(username)
                .companyCode(companyCode)
                .date(java.time.LocalDate.parse(dateStr))
                .type(TransactionType.RIGHTS)
                .count(count)
                .price(price)
                .commission(BigDecimal.ZERO)
                .createdAt(LocalDateTime.now())
                .build();
        transaction = transactionRepository.save(transaction);

        // Create rights record
        Rights rights = Rights.builder()
                .userId(username)
                .companyCode(companyCode)
                .date(java.time.LocalDate.parse(dateStr))
                .count(count)
                .price(price)
                .transactionId(transaction.getId())
                .createdAt(LocalDateTime.now())
                .build();

        return ResponseEntity.status(HttpStatus.CREATED).body(rightsRepository.save(rights));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Rights> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Rights rights = rightsRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Rights not found: " + id));
        if (!rights.getUserId().equals(currentUsername())) {
            throw new RuntimeException("Access denied");
        }

        String dateStr = (String) body.get("date");
        int count = ((Number) body.get("count")).intValue();
        BigDecimal price = new BigDecimal(body.get("price").toString());

        rights.setDate(java.time.LocalDate.parse(dateStr));
        rights.setCount(count);
        rights.setPrice(price);
        rightsRepository.save(rights);

        // Update linked transaction
        if (rights.getTransactionId() != null) {
            transactionRepository.findById(rights.getTransactionId()).ifPresent(tx -> {
                tx.setDate(rights.getDate());
                tx.setCount(count);
                tx.setPrice(price);
                transactionRepository.save(tx);
            });
        }

        return ResponseEntity.ok(rights);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        Rights rights = rightsRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Rights not found: " + id));
        if (!rights.getUserId().equals(currentUsername())) {
            throw new RuntimeException("Access denied");
        }

        // Delete linked transaction
        if (rights.getTransactionId() != null) {
            transactionRepository.deleteById(rights.getTransactionId());
        }

        rightsRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
