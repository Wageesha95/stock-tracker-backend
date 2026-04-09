package com.personal.stocktracker.service;

import com.personal.stocktracker.document.Company;
import com.personal.stocktracker.document.Transaction;
import com.personal.stocktracker.dto.TransactionRequest;
import com.personal.stocktracker.repository.CompanyRepository;
import com.personal.stocktracker.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final CompanyRepository companyRepository;

    public List<Transaction> getAllTransactions(String userId) {
        return transactionRepository.findByUserIdOrderByDateDesc(userId);
    }

    public Transaction createTransaction(TransactionRequest request, String userId) {
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

        Transaction transaction = Transaction.builder()
                .userId(userId)
                .companyCode(code)
                .date(request.getDate())
                .type(request.getType())
                .count(request.getCount())
                .price(request.getPrice())
                .commission(request.getCommission())
                .createdAt(LocalDateTime.now())
                .build();

        return transactionRepository.save(transaction);
    }

    public List<Transaction> getTransactionsByCompany(String userId, String companyCode) {
        return transactionRepository.findByUserIdAndCompanyCodeOrderByDateDesc(userId, companyCode);
    }

    public void deleteTransaction(String id, String userId) {
        Transaction transaction = transactionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Transaction not found with id: " + id));
        if (!transaction.getUserId().equals(userId)) {
            throw new RuntimeException("Access denied");
        }
        transactionRepository.deleteById(id);
    }
}
