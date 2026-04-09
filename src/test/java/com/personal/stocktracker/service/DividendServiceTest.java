package com.personal.stocktracker.service;

import com.personal.stocktracker.document.Company;
import com.personal.stocktracker.document.Dividend;
import com.personal.stocktracker.document.DividendType;
import com.personal.stocktracker.dto.DividendRequest;
import com.personal.stocktracker.repository.CompanyRepository;
import com.personal.stocktracker.repository.DividendRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DividendServiceTest {

    @Mock
    private DividendRepository dividendRepository;

    @Mock
    private CompanyRepository companyRepository;

    @InjectMocks
    private DividendService dividendService;

    @Test
    void getAllDividends_returnsUserDividends() {
        when(dividendRepository.findByUserIdOrderByDateDesc("user1"))
                .thenReturn(List.of(Dividend.builder().id("1").companyCode("JKH").build()));

        List<Dividend> result = dividendService.getAllDividends("user1");

        assertThat(result).hasSize(1);
    }

    @Test
    void createDividend_cash_usesProvidedTotalAmount() {
        DividendRequest request = new DividendRequest();
        request.setCompanyCode("jkh");
        request.setType(DividendType.CASH);
        request.setDate(LocalDate.of(2026, 3, 1));
        request.setAmount(new BigDecimal("10.00"));
        request.setShares(100);
        request.setTotalAmount(new BigDecimal("850.00")); // after 15% tax

        when(companyRepository.existsByCode("JKH")).thenReturn(true);
        when(dividendRepository.save(any(Dividend.class))).thenAnswer(i -> i.getArgument(0));

        Dividend result = dividendService.createDividend(request, "user1");

        assertThat(result.getTotalAmount()).isEqualByComparingTo(new BigDecimal("850.00"));
        assertThat(result.getUserId()).isEqualTo("user1");
    }

    @Test
    void createDividend_cash_calculatesTotalWhenNotProvided() {
        DividendRequest request = new DividendRequest();
        request.setCompanyCode("JKH");
        request.setType(DividendType.CASH);
        request.setDate(LocalDate.of(2026, 3, 1));
        request.setAmount(new BigDecimal("10.00"));
        request.setShares(100);

        when(companyRepository.existsByCode("JKH")).thenReturn(true);
        when(dividendRepository.save(any(Dividend.class))).thenAnswer(i -> i.getArgument(0));

        Dividend result = dividendService.createDividend(request, "user1");

        assertThat(result.getTotalAmount()).isEqualByComparingTo(new BigDecimal("1000.00"));
    }

    @Test
    void createDividend_scrip_setsScrip() {
        DividendRequest request = new DividendRequest();
        request.setCompanyCode("JKH");
        request.setType(DividendType.SCRIP);
        request.setDate(LocalDate.of(2026, 3, 1));
        request.setScripShares(10);

        when(companyRepository.existsByCode("JKH")).thenReturn(true);
        when(dividendRepository.save(any(Dividend.class))).thenAnswer(i -> i.getArgument(0));

        Dividend result = dividendService.createDividend(request, "user1");

        assertThat(result.getScripShares()).isEqualTo(10);
        assertThat(result.getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void createDividend_autoCreatesCompany() {
        DividendRequest request = new DividendRequest();
        request.setCompanyCode("new");
        request.setType(DividendType.CASH);
        request.setDate(LocalDate.now());
        request.setAmount(new BigDecimal("5.00"));
        request.setShares(50);

        when(companyRepository.existsByCode("NEW")).thenReturn(false);
        when(dividendRepository.save(any(Dividend.class))).thenAnswer(i -> i.getArgument(0));

        dividendService.createDividend(request, "user1");

        verify(companyRepository).save(any(Company.class));
    }

    @Test
    void deleteDividend_throwsWhenNotFound() {
        when(dividendRepository.findById("missing")).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> dividendService.deleteDividend("missing", "user1"))
                .isInstanceOf(RuntimeException.class);
    }
}
