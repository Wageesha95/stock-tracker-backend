package com.personal.stocktracker.service;

import com.personal.stocktracker.document.Company;
import com.personal.stocktracker.dto.CompanyRequest;
import com.personal.stocktracker.repository.CompanyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CompanyService {

    private final CompanyRepository companyRepository;

    public List<Company> getAllCompanies() {
        return companyRepository.findAll();
    }

    public Company getByCode(String code) {
        return companyRepository.findByCode(code)
                .orElseThrow(() -> new RuntimeException("Company not found with code: " + code));
    }

    public Company createCompany(CompanyRequest request) {
        if (companyRepository.existsByCode(request.getCode())) {
            throw new RuntimeException("Company already exists with code: " + request.getCode());
        }

        Company company = Company.builder()
                .code(request.getCode().toUpperCase())
                .name(request.getName())
                .createdAt(LocalDateTime.now())
                .build();

        return companyRepository.save(company);
    }

    public Company updateCompany(String id, CompanyRequest request) {
        Company company = companyRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Company not found with id: " + id));
        company.setCode(request.getCode().toUpperCase());
        company.setName(request.getName());
        company.setIndustryGroupId(request.getIndustryGroupId());
        return companyRepository.save(company);
    }

    public Company updateLogoUrl(String code, String logoUrl) {
        Company company = companyRepository.findByCode(code)
                .orElseThrow(() -> new RuntimeException("Company not found with code: " + code));
        company.setLogoUrl(logoUrl);
        return companyRepository.save(company);
    }

    public void deleteCompany(String id) {
        if (!companyRepository.existsById(id)) {
            throw new RuntimeException("Company not found with id: " + id);
        }
        companyRepository.deleteById(id);
    }
}
