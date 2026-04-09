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
import java.util.Set;

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

    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/webp");

    @PostMapping("/{code}/logo")
    public ResponseEntity<Company> uploadLogo(@PathVariable String code,
                                               @RequestParam("file") MultipartFile file) throws IOException {
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_IMAGE_TYPES.contains(contentType)) {
            return ResponseEntity.badRequest().build();
        }
        if (file.getSize() > 2 * 1024 * 1024) {
            return ResponseEntity.badRequest().build();
        }

        Path logosDir = Paths.get("logos");
        Files.createDirectories(logosDir);

        String filename = code.toUpperCase().replaceAll("[^A-Z0-9._-]", "") + ".png";
        Path target = logosDir.resolve(filename);
        if (!target.normalize().startsWith(logosDir.normalize())) {
            return ResponseEntity.badRequest().build();
        }
        Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);

        String logoUrl = "/logos/" + filename;
        Company updated = companyService.updateLogoUrl(code, logoUrl);
        return ResponseEntity.ok(updated);
    }
}
