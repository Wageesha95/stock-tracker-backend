package com.personal.stocktracker.controller;

import com.personal.stocktracker.document.Company;
import com.personal.stocktracker.dto.CompanyRequest;
import com.personal.stocktracker.service.CompanyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;

@RestController
@RequestMapping("/api/companies")
@RequiredArgsConstructor
public class CompanyController {

    private final CompanyService companyService;

    @GetMapping
    public ResponseEntity<List<Company>> getAllCompanies() {
        return ResponseEntity.ok(companyService.getAllCompanies());
    }

    @PostMapping
    public ResponseEntity<Company> createCompany(@Valid @RequestBody CompanyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(companyService.createCompany(request));
    }

    @GetMapping("/{code}")
    public ResponseEntity<Company> getByCode(@PathVariable String code) {
        return ResponseEntity.ok(companyService.getByCode(code));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Company> updateCompany(@PathVariable String id, @Valid @RequestBody CompanyRequest request) {
        return ResponseEntity.ok(companyService.updateCompany(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCompany(@PathVariable String id) {
        companyService.deleteCompany(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{code}/logo")
    public ResponseEntity<Company> uploadLogo(@PathVariable String code,
                                               @RequestParam("file") MultipartFile file) throws IOException {
        Path logosDir = Paths.get("logos");
        Files.createDirectories(logosDir);

        String filename = code.toUpperCase() + ".png";
        Path target = logosDir.resolve(filename);
        Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);

        String logoUrl = "/logos/" + filename;
        Company updated = companyService.updateLogoUrl(code, logoUrl);
        return ResponseEntity.ok(updated);
    }
}
