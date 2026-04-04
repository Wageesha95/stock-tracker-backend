package com.personal.stocktracker.service;

import com.personal.stocktracker.document.Company;
import com.personal.stocktracker.document.Dividend;
import com.personal.stocktracker.document.DividendType;
import com.personal.stocktracker.dto.DividendRequest;
import com.personal.stocktracker.repository.CompanyRepository;
import com.personal.stocktracker.repository.DividendRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DividendService {

    private final DividendRepository dividendRepository;
    private final CompanyRepository companyRepository;

    public List<Dividend> getAllDividends(String userId) {
        return dividendRepository.findByUserIdOrderByDateDesc(userId);
    }

    public Dividend createDividend(DividendRequest request, String userId) {
        String code = request.getCompanyCode().toUpperCase();

        // Auto-create company if it doesn't exist
        if (!companyRepository.existsByCode(code)) {
            Company company = Company.builder()
                    .code(code)
                    .name(code)
                    .createdAt(LocalDateTime.now())
                    .build();
            companyRepository.save(company);
        }
        BigDecimal totalAmount = BigDecimal.ZERO;
        if (request.getType() == DividendType.CASH) {
            if (request.getTotalAmount() != null && request.getTotalAmount().compareTo(BigDecimal.ZERO) > 0) {
                totalAmount = request.getTotalAmount();
            } else if (request.getAmount() != null && request.getShares() != null) {
                totalAmount = request.getAmount().multiply(BigDecimal.valueOf(request.getShares()));
            }
        }

        Dividend dividend = Dividend.builder()
                .userId(userId)
                .companyCode(request.getCompanyCode().toUpperCase())
                .type(request.getType())
                .amount(request.getType() == DividendType.CASH ? request.getAmount() : BigDecimal.ZERO)
                .date(request.getDate())
                .shares(request.getType() == DividendType.CASH ? request.getShares() : 0)
                .scripShares(request.getType() == DividendType.SCRIP ? request.getScripShares() : 0)
                .totalAmount(totalAmount)
                .taxed(request.getTaxed() != null ? request.getTaxed() : true)
                .createdAt(LocalDateTime.now())
                .build();

        return dividendRepository.save(dividend);
    }

    public List<Dividend> getDividendsByCompany(String userId, String companyCode) {
        return dividendRepository.findByUserIdAndCompanyCodeOrderByDateDesc(userId, companyCode);
    }

    public void deleteDividend(String id) {
        if (!dividendRepository.existsById(id)) {
            throw new RuntimeException("Dividend not found with id: " + id);
        }
        dividendRepository.deleteById(id);
    }
}
