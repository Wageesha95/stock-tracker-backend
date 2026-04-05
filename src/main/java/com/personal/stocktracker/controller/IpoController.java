package com.personal.stocktracker.controller;

import com.personal.stocktracker.document.Company;
import com.personal.stocktracker.document.Ipo;
import com.personal.stocktracker.document.Transaction;
import com.personal.stocktracker.document.TransactionType;
import com.personal.stocktracker.repository.CompanyRepository;
import com.personal.stocktracker.repository.IpoRepository;
import com.personal.stocktracker.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ipos")
@RequiredArgsConstructor
public class IpoController {

    private final IpoRepository ipoRepository;
    private final TransactionRepository transactionRepository;
    private final CompanyRepository companyRepository;

    private String currentUsername() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    @GetMapping
    public ResponseEntity<List<Ipo>> getAll() {
        return ResponseEntity.ok(ipoRepository.findByUserIdOrderByDateDesc(currentUsername()));
    }

    @PostMapping
    public ResponseEntity<Ipo> create(@RequestBody Map<String, Object> body) {
        String username = currentUsername();
        String companyCode = ((String) body.get("companyCode")).toUpperCase();
        String dateStr = (String) body.get("date");
        int count = ((Number) body.get("count")).intValue();
        BigDecimal price = new BigDecimal(body.get("price").toString());

        if (!companyRepository.existsByCode(companyCode)) {
            companyRepository.save(Company.builder()
                    .code(companyCode).name(companyCode)
                    .createdAt(LocalDateTime.now()).build());
        }

        Transaction transaction = Transaction.builder()
                .userId(username)
                .companyCode(companyCode)
                .date(java.time.LocalDate.parse(dateStr))
                .type(TransactionType.IPO)
                .count(count)
                .price(price)
                .commission(BigDecimal.ZERO)
                .createdAt(LocalDateTime.now())
                .build();
        transaction = transactionRepository.save(transaction);

        Ipo ipo = Ipo.builder()
                .userId(username)
                .companyCode(companyCode)
                .date(java.time.LocalDate.parse(dateStr))
                .count(count)
                .price(price)
                .transactionId(transaction.getId())
                .createdAt(LocalDateTime.now())
                .build();

        return ResponseEntity.status(HttpStatus.CREATED).body(ipoRepository.save(ipo));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Ipo> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Ipo ipo = ipoRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("IPO not found: " + id));
        if (!ipo.getUserId().equals(currentUsername())) {
            throw new RuntimeException("Access denied");
        }

        String dateStr = (String) body.get("date");
        int count = ((Number) body.get("count")).intValue();
        BigDecimal price = new BigDecimal(body.get("price").toString());

        ipo.setDate(java.time.LocalDate.parse(dateStr));
        ipo.setCount(count);
        ipo.setPrice(price);
        ipoRepository.save(ipo);

        if (ipo.getTransactionId() != null) {
            transactionRepository.findById(ipo.getTransactionId()).ifPresent(tx -> {
                tx.setDate(ipo.getDate());
                tx.setCount(count);
                tx.setPrice(price);
                transactionRepository.save(tx);
            });
        }

        return ResponseEntity.ok(ipo);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        Ipo ipo = ipoRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("IPO not found: " + id));
        if (!ipo.getUserId().equals(currentUsername())) {
            throw new RuntimeException("Access denied");
        }

        if (ipo.getTransactionId() != null) {
            transactionRepository.deleteById(ipo.getTransactionId());
        }

        ipoRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
