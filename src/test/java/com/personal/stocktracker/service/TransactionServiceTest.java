package com.personal.stocktracker.service;

import com.personal.stocktracker.document.Company;
import com.personal.stocktracker.document.Transaction;
import com.personal.stocktracker.document.TransactionType;
import com.personal.stocktracker.dto.TransactionRequest;
import com.personal.stocktracker.repository.CompanyRepository;
import com.personal.stocktracker.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private CompanyRepository companyRepository;

    @InjectMocks
    private TransactionService transactionService;

    @Test
    void getAllTransactions_returnsUserTransactions() {
        List<Transaction> expected = List.of(
                Transaction.builder().id("1").userId("user1").companyCode("JKH").build()
        );
        when(transactionRepository.findByUserIdOrderByDateDesc("user1")).thenReturn(expected);

        List<Transaction> result = transactionService.getAllTransactions("user1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCompanyCode()).isEqualTo("JKH");
        verify(transactionRepository).findByUserIdOrderByDateDesc("user1");
    }

    @Test
    void createTransaction_autoCreatesCompany_whenNotExists() {
        TransactionRequest request = new TransactionRequest();
        request.setCompanyCode("jkh");
        request.setDate(LocalDate.of(2026, 1, 15));
        request.setType(TransactionType.BUY);
        request.setCount(100);
        request.setPrice(new BigDecimal("150.00"));
        request.setCommission(new BigDecimal("1.22"));

        when(companyRepository.existsByCode("JKH")).thenReturn(false);
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> {
            Transaction t = i.getArgument(0);
            t.setId("tx1");
            return t;
        });

        Transaction result = transactionService.createTransaction(request, "user1");

        verify(companyRepository).save(any(Company.class));
        assertThat(result.getCompanyCode()).isEqualTo("JKH");
        assertThat(result.getUserId()).isEqualTo("user1");
        assertThat(result.getType()).isEqualTo(TransactionType.BUY);
        assertThat(result.getCount()).isEqualTo(100);
    }

    @Test
    void createTransaction_doesNotCreateCompany_whenExists() {
        TransactionRequest request = new TransactionRequest();
        request.setCompanyCode("JKH");
        request.setDate(LocalDate.of(2026, 1, 15));
        request.setType(TransactionType.BUY);
        request.setCount(50);
        request.setPrice(new BigDecimal("100.00"));
        request.setCommission(new BigDecimal("0.50"));

        when(companyRepository.existsByCode("JKH")).thenReturn(true);
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        transactionService.createTransaction(request, "user1");

        verify(companyRepository, never()).save(any(Company.class));
    }

    @Test
    void createTransaction_uppercasesCompanyCode() {
        TransactionRequest request = new TransactionRequest();
        request.setCompanyCode("hnb");
        request.setDate(LocalDate.now());
        request.setType(TransactionType.SELL);
        request.setCount(10);
        request.setPrice(new BigDecimal("200.00"));
        request.setCommission(BigDecimal.ZERO);

        when(companyRepository.existsByCode("HNB")).thenReturn(true);
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        Transaction result = transactionService.createTransaction(request, "user1");

        assertThat(result.getCompanyCode()).isEqualTo("HNB");
    }

    @Test
    void getTransactionsByCompany_filtersCorrectly() {
        List<Transaction> expected = List.of(
                Transaction.builder().id("1").userId("user1").companyCode("JKH").build()
        );
        when(transactionRepository.findByUserIdAndCompanyCodeOrderByDateDesc("user1", "JKH"))
                .thenReturn(expected);

        List<Transaction> result = transactionService.getTransactionsByCompany("user1", "JKH");

        assertThat(result).hasSize(1);
    }

    @Test
    void deleteTransaction_throwsWhenNotFound() {
        when(transactionRepository.findById("missing")).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> transactionService.deleteTransaction("missing", "user1"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Transaction not found");
    }

    @Test
    void deleteTransaction_deletesWhenFound() {
        Transaction tx = Transaction.builder().id("tx1").userId("user1").build();
        when(transactionRepository.findById("tx1")).thenReturn(java.util.Optional.of(tx));

        transactionService.deleteTransaction("tx1", "user1");

        verify(transactionRepository).deleteById("tx1");
    }
}
