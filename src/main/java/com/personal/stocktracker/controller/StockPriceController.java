package com.personal.stocktracker.controller;

import com.personal.stocktracker.document.StockPrice;
import com.personal.stocktracker.dto.StockPriceRequest;
import com.personal.stocktracker.service.StockPriceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/stock-prices")
@RequiredArgsConstructor
public class StockPriceController {

    private final StockPriceService stockPriceService;

    @PostMapping
    public ResponseEntity<StockPrice> addStockPrice(@Valid @RequestBody StockPriceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(stockPriceService.addStockPrice(request));
    }

    @GetMapping("/company/{code}")
    public ResponseEntity<List<StockPrice>> getPriceHistory(@PathVariable String code) {
        return ResponseEntity.ok(stockPriceService.getPriceHistory(code));
    }

    @GetMapping("/company/{code}/latest")
    public ResponseEntity<StockPrice> getLatestPrice(@PathVariable String code) {
        return ResponseEntity.ok(stockPriceService.getLatestPrice(code));
    }
}
