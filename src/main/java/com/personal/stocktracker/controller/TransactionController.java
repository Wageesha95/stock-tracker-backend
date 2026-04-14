package com.personal.stocktracker.controller;

import com.personal.stocktracker.document.Transaction;
import com.personal.stocktracker.dto.TransactionRequest;
import com.personal.stocktracker.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    private String currentUsername() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    @GetMapping
    public ResponseEntity<List<Transaction>> getAllTransactions() {
        return ResponseEntity.ok(transactionService.getAllTransactions(currentUsername()));
    }

    @PostMapping
    public ResponseEntity<Transaction> createTransaction(@Valid @RequestBody TransactionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(transactionService.createTransaction(request, currentUsername()));
    }

    @GetMapping("/company/{code}")
    public ResponseEntity<List<Transaction>> getTransactionsByCompany(@PathVariable String code) {
        return ResponseEntity.ok(transactionService.getTransactionsByCompany(currentUsername(), code.toUpperCase()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTransaction(@PathVariable String id) {
        transactionService.deleteTransaction(id, currentUsername());
        return ResponseEntity.noContent().build();
    }
}
