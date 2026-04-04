package com.personal.stocktracker.service;

import com.personal.stocktracker.document.Company;
import com.personal.stocktracker.dto.CompanyRequest;
import com.personal.stocktracker.repository.CompanyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompanyServiceTest {

    @Mock
    private CompanyRepository companyRepository;

    @InjectMocks
    private CompanyService companyService;

    @Test
    void getAllCompanies_returnsList() {
        when(companyRepository.findAll()).thenReturn(List.of(
                Company.builder().code("JKH").name("John Keells").build()
        ));

        List<Company> result = companyService.getAllCompanies();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCode()).isEqualTo("JKH");
    }

    @Test
    void getByCode_returnsCompany() {
        Company company = Company.builder().code("JKH").name("John Keells").build();
        when(companyRepository.findByCode("JKH")).thenReturn(Optional.of(company));

        Company result = companyService.getByCode("JKH");

        assertThat(result.getName()).isEqualTo("John Keells");
    }

    @Test
    void getByCode_throwsWhenNotFound() {
        when(companyRepository.findByCode("XXX")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> companyService.getByCode("XXX"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Company not found");
    }

    @Test
    void createCompany_uppercasesCode() {
        CompanyRequest request = new CompanyRequest("jkh", "John Keells", null);
        when(companyRepository.existsByCode("jkh")).thenReturn(false);
        when(companyRepository.save(any(Company.class))).thenAnswer(i -> {
            Company c = i.getArgument(0);
            c.setId("id1");
            return c;
        });

        Company result = companyService.createCompany(request);

        assertThat(result.getCode()).isEqualTo("JKH");
    }

    @Test
    void createCompany_throwsWhenDuplicate() {
        CompanyRequest request = new CompanyRequest("JKH", "John Keells", null);
        when(companyRepository.existsByCode("JKH")).thenReturn(true);

        assertThatThrownBy(() -> companyService.createCompany(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void updateCompany_setsIndustryGroupId() {
        Company existing = Company.builder().id("id1").code("JKH").name("Old Name").build();
        when(companyRepository.findById("id1")).thenReturn(Optional.of(existing));
        when(companyRepository.save(any(Company.class))).thenAnswer(i -> i.getArgument(0));

        CompanyRequest request = new CompanyRequest("JKH", "New Name", "group1");
        Company result = companyService.updateCompany("id1", request);

        assertThat(result.getName()).isEqualTo("New Name");
        assertThat(result.getIndustryGroupId()).isEqualTo("group1");
    }

    @Test
    void deleteCompany_throwsWhenNotFound() {
        when(companyRepository.existsById("missing")).thenReturn(false);

        assertThatThrownBy(() -> companyService.deleteCompany("missing"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void deleteCompany_deletesWhenFound() {
        when(companyRepository.existsById("id1")).thenReturn(true);

        companyService.deleteCompany("id1");

        verify(companyRepository).deleteById("id1");
    }
}
