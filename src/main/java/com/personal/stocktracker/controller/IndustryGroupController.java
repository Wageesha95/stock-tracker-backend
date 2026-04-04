package com.personal.stocktracker.controller;

import com.personal.stocktracker.document.Company;
import com.personal.stocktracker.document.IndustryGroup;
import com.personal.stocktracker.repository.CompanyRepository;
import com.personal.stocktracker.repository.IndustryGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/industry-groups")
@RequiredArgsConstructor
public class IndustryGroupController {

    private final IndustryGroupRepository industryGroupRepository;
    private final CompanyRepository companyRepository;

    @GetMapping
    public ResponseEntity<List<IndustryGroup>> getAll() {
        return ResponseEntity.ok(industryGroupRepository.findAll());
    }

    @PostMapping
    public ResponseEntity<IndustryGroup> create(@RequestBody Map<String, String> body) {
        String name = body.get("name");
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (industryGroupRepository.existsByName(name.trim())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        IndustryGroup group = IndustryGroup.builder().name(name.trim()).build();
        return ResponseEntity.status(HttpStatus.CREATED).body(industryGroupRepository.save(group));
    }

    @PutMapping("/{id}")
    public ResponseEntity<IndustryGroup> update(@PathVariable String id, @RequestBody Map<String, String> body) {
        IndustryGroup group = industryGroupRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Industry group not found: " + id));
        String name = body.get("name");
        if (name != null && !name.isBlank()) {
            group.setName(name.trim());
        }
        return ResponseEntity.ok(industryGroupRepository.save(group));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        industryGroupRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Industry group not found: " + id));
        // Unlink companies
        companyRepository.findAll().stream()
                .filter(c -> id.equals(c.getIndustryGroupId()))
                .forEach(c -> {
                    c.setIndustryGroupId(null);
                    companyRepository.save(c);
                });
        industryGroupRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/import")
    public ResponseEntity<Map<String, Integer>> importGics(@RequestBody List<Map<String, String>> data) {
        // Clear all old groups and company links first
        companyRepository.findAll().forEach(c -> {
            c.setIndustryGroupId(null);
            companyRepository.save(c);
        });
        industryGroupRepository.deleteAll();

        int count = 0;
        for (Map<String, String> entry : data) {
            String code = entry.get("code");
            String gics = entry.get("gics");
            if (code == null || gics == null || gics.isBlank()) continue;

            // Find or create industry group
            IndustryGroup group = industryGroupRepository.findByName(gics)
                    .orElseGet(() -> industryGroupRepository.save(
                            IndustryGroup.builder().name(gics).build()));

            // Link to company
            companyRepository.findByCode(code).ifPresent(company -> {
                company.setIndustryGroupId(group.getId());
                companyRepository.save(company);
            });
            count++;
        }
        return ResponseEntity.ok(Map.of("updated", count));
    }
}
