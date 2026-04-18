package com.personal.stocktracker.service;

import com.personal.stocktracker.document.Company;
import com.personal.stocktracker.document.Dividend;
import com.personal.stocktracker.document.DividendType;
import com.personal.stocktracker.document.Transaction;
import com.personal.stocktracker.document.TransactionType;
import com.personal.stocktracker.dto.DividendRequest;
import com.personal.stocktracker.repository.CompanyRepository;
import com.personal.stocktracker.repository.DividendRepository;
import com.personal.stocktracker.repository.TransactionRepository;
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
    private final TransactionRepository transactionRepository;

    public List<Dividend> getAllDividends(String userId) {
        return dividendRepository.findByUserIdOrderByDateDesc(userId);
    }

    public Dividend createDividend(DividendRequest request, String userId) {
        String code = request.getCompanyCode().toUpperCase();

        if (request.getXdDate() != null && dividendRepository.existsByUserIdAndCompanyCodeAndTypeAndXdDate(userId, code, request.getType(), request.getXdDate())) {
            throw new RuntimeException("Dividend already exists for " + code + " with XD date " + request.getXdDate() + " and type " + request.getType());
        }

        if (!companyRepository.existsByCode(code)) {
            companyRepository.save(Company.builder()
                    .code(code).name(code).createdAt(LocalDateTime.now()).build());
        }

        BigDecimal totalAmount = BigDecimal.ZERO;
        if (request.getType() == DividendType.CASH) {
            if (request.getTotalAmount() != null && request.getTotalAmount().compareTo(BigDecimal.ZERO) > 0) {
                totalAmount = request.getTotalAmount();
            } else if (request.getAmount() != null && request.getShares() != null) {
                totalAmount = request.getAmount().multiply(BigDecimal.valueOf(request.getShares()));
            }
        }

        String transactionId = null;

        // Auto-create linked transaction for SCRIP dividends
        if (request.getType() == DividendType.SCRIP && request.getScripShares() != null && request.getScripShares() > 0) {
            Transaction tx = Transaction.builder()
                    .userId(userId)
                    .companyCode(code)
                    .date(request.getDate())
                    .type(TransactionType.SCRIP_DIVIDEND)
                    .count(request.getScripShares())
                    .price(BigDecimal.ZERO)
                    .commission(BigDecimal.ZERO)
                    .createdAt(LocalDateTime.now())
                    .build();
            tx = transactionRepository.save(tx);
            transactionId = tx.getId();
        }

        Dividend dividend = Dividend.builder()
                .userId(userId)
                .companyCode(code)
                .type(request.getType())
                .amount(request.getType() == DividendType.CASH ? request.getAmount() : BigDecimal.ZERO)
                .date(request.getDate())
                .xdDate(request.getXdDate())
                .shares(request.getType() == DividendType.CASH ? request.getShares() : 0)
                .scripShares(request.getType() == DividendType.SCRIP ? request.getScripShares() : 0)
                .totalAmount(totalAmount)
                .taxed(request.getTaxed() != null ? request.getTaxed() : true)
                .transactionId(transactionId)
                .createdAt(LocalDateTime.now())
                .build();

        return dividendRepository.save(dividend);
    }

    public List<Dividend> getDividendsByCompany(String userId, String companyCode) {
        return dividendRepository.findByUserIdAndCompanyCodeOrderByDateDesc(userId, companyCode);
    }

    public Dividend updateDividend(String id, DividendRequest request, String userId) {
        Dividend dividend = dividendRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Dividend not found with id: " + id));
        if (!dividend.getUserId().equals(userId)) {
            throw new RuntimeException("Access denied");
        }

        dividend.setType(request.getType());
        dividend.setDate(request.getDate());
        dividend.setXdDate(request.getXdDate());
        dividend.setAmount(request.getType() == DividendType.CASH ? request.getAmount() : BigDecimal.ZERO);
        dividend.setShares(request.getType() == DividendType.CASH ? request.getShares() : 0);
        dividend.setScripShares(request.getType() == DividendType.SCRIP ? request.getScripShares() : 0);

        if (request.getTotalAmount() != null && request.getTotalAmount().compareTo(BigDecimal.ZERO) > 0) {
            dividend.setTotalAmount(request.getTotalAmount());
        } else if (request.getType() == DividendType.CASH && request.getAmount() != null && request.getShares() != null) {
            dividend.setTotalAmount(request.getAmount().multiply(BigDecimal.valueOf(request.getShares())));
        }
        dividend.setTaxed(request.getTaxed() != null ? request.getTaxed() : true);

        // Handle linked transaction for SCRIP
        if (request.getType() == DividendType.SCRIP && request.getScripShares() != null && request.getScripShares() > 0) {
            if (dividend.getTransactionId() != null) {
                // Update existing linked transaction
                transactionRepository.findById(dividend.getTransactionId()).ifPresent(tx -> {
                    tx.setDate(request.getDate());
                    tx.setCount(request.getScripShares());
                    transactionRepository.save(tx);
                });
            } else {
                // Create new linked transaction
                Transaction tx = Transaction.builder()
                        .userId(dividend.getUserId())
                        .companyCode(dividend.getCompanyCode())
                        .date(request.getDate())
                        .type(TransactionType.SCRIP_DIVIDEND)
                        .count(request.getScripShares())
                        .price(BigDecimal.ZERO)
                        .commission(BigDecimal.ZERO)
                        .createdAt(LocalDateTime.now())
                        .build();
                tx = transactionRepository.save(tx);
                dividend.setTransactionId(tx.getId());
            }
        } else if (dividend.getTransactionId() != null) {
            // Type changed from SCRIP to CASH — delete linked transaction
            transactionRepository.deleteById(dividend.getTransactionId());
            dividend.setTransactionId(null);
        }

        return dividendRepository.save(dividend);
    }

    public void deleteDividend(String id, String userId) {
        Dividend dividend = dividendRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Dividend not found with id: " + id));
        if (!dividend.getUserId().equals(userId)) {
            throw new RuntimeException("Access denied");
        }

        // Delete linked transaction if exists
        if (dividend.getTransactionId() != null) {
            transactionRepository.deleteById(dividend.getTransactionId());
        }

        dividendRepository.deleteById(id);
    }
}
