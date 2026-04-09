package com.personal.stocktracker.controller;

import com.personal.stocktracker.document.Dividend;
import com.personal.stocktracker.dto.DividendRequest;
import com.personal.stocktracker.service.DividendService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/dividends")
@RequiredArgsConstructor
public class DividendController {

    private final DividendService dividendService;

    private String currentUsername() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    @GetMapping
    public ResponseEntity<List<Dividend>> getAllDividends() {
        return ResponseEntity.ok(dividendService.getAllDividends(currentUsername()));
    }

    @PostMapping
    public ResponseEntity<Dividend> createDividend(@Valid @RequestBody DividendRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(dividendService.createDividend(request, currentUsername()));
    }

    @GetMapping("/company/{code}")
    public ResponseEntity<List<Dividend>> getDividendsByCompany(@PathVariable String code) {
        return ResponseEntity.ok(dividendService.getDividendsByCompany(currentUsername(), code));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Dividend> updateDividend(@PathVariable String id, @Valid @RequestBody DividendRequest request) {
        return ResponseEntity.ok(dividendService.updateDividend(id, request, currentUsername()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDividend(@PathVariable String id) {
        dividendService.deleteDividend(id, currentUsername());
        return ResponseEntity.noContent().build();
    }
}
