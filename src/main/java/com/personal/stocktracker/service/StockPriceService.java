package com.personal.stocktracker.service;

import com.personal.stocktracker.document.StockPrice;
import com.personal.stocktracker.dto.StockPriceRequest;
import com.personal.stocktracker.repository.StockPriceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StockPriceService {

    private final StockPriceRepository stockPriceRepository;

    public StockPrice addStockPrice(StockPriceRequest request) {
        StockPrice stockPrice = StockPrice.builder()
                .companyCode(request.getCompanyCode().toUpperCase())
                .price(request.getPrice())
                .date(request.getDate())
                .createdAt(LocalDateTime.now())
                .build();

        return stockPriceRepository.save(stockPrice);
    }

    public List<StockPrice> getPriceHistory(String companyCode) {
        return stockPriceRepository.findByCompanyCodeOrderByDateDesc(companyCode);
    }

    public StockPrice getLatestPrice(String companyCode) {
        return stockPriceRepository.findFirstByCompanyCodeOrderByDateDescCreatedAtDesc(companyCode)
                .orElseThrow(() -> new RuntimeException("No stock price found for company: " + companyCode));
    }
}
