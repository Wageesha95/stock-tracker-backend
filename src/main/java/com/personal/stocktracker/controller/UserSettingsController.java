package com.personal.stocktracker.controller;

import com.personal.stocktracker.document.UserSettings;
import com.personal.stocktracker.repository.UserSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class UserSettingsController {

    private final UserSettingsRepository userSettingsRepository;

    private String currentUsername() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    @GetMapping
    public ResponseEntity<UserSettings> get() {
        UserSettings settings = userSettingsRepository.findByUserId(currentUsername())
                .orElse(UserSettings.builder().userId(currentUsername()).build());
        return ResponseEntity.ok(settings);
    }

    @PutMapping("/brokers")
    public ResponseEntity<UserSettings> updateSelectedBrokers(@RequestBody Map<String, List<String>> body) {
        List<String> brokerIds = body.getOrDefault("selectedBrokerIds", List.of());
        UserSettings settings = userSettingsRepository.findByUserId(currentUsername())
                .orElse(UserSettings.builder().userId(currentUsername()).build());
        settings.setSelectedBrokerIds(brokerIds);
        return ResponseEntity.ok(userSettingsRepository.save(settings));
    }

    @PutMapping("/data-brokers")
    public ResponseEntity<UserSettings> updateSelectedDataBrokers(@RequestBody Map<String, List<String>> body) {
        List<String> brokerIds = body.getOrDefault("selectedDataBrokerIds", List.of());
        UserSettings settings = userSettingsRepository.findByUserId(currentUsername())
                .orElse(UserSettings.builder().userId(currentUsername()).build());
        settings.setSelectedDataBrokerIds(brokerIds);
        return ResponseEntity.ok(userSettingsRepository.save(settings));
    }

    @PutMapping("/table-columns")
    public ResponseEntity<UserSettings> updateTableColumns(@RequestBody Map<String, List<String>> body) {
        UserSettings settings = userSettingsRepository.findByUserId(currentUsername())
                .orElse(UserSettings.builder().userId(currentUsername()).build());
        settings.setTableColumns(body);
        return ResponseEntity.ok(userSettingsRepository.save(settings));
    }

    @PutMapping("/opportunity-cost-rate")
    public ResponseEntity<UserSettings> updateOpportunityCostRate(@RequestBody Map<String, Double> body) {
        Double rate = body.get("opportunityCostRate");
        if (rate == null || rate < 0 || rate > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Opportunity cost rate must be between 0 and 100");
        }
        UserSettings settings = userSettingsRepository.findByUserId(currentUsername())
                .orElse(UserSettings.builder().userId(currentUsername()).build());
        settings.setOpportunityCostRate(rate);
        return ResponseEntity.ok(userSettingsRepository.save(settings));
    }

    @PutMapping("/company-ttm-weeks/{companyCode}")
    public ResponseEntity<UserSettings> updateCompanyTtmWeeks(
            @PathVariable String companyCode,
            @RequestBody Map<String, Integer> body) {
        Integer weeks = body.get("weeks");
        UserSettings settings = userSettingsRepository.findByUserId(currentUsername())
                .orElse(UserSettings.builder().userId(currentUsername()).build());
        if (settings.getCompanyTtmWeeks() == null) {
            settings.setCompanyTtmWeeks(new HashMap<>());
        }
        if (weeks == null || weeks <= 0) {
            settings.getCompanyTtmWeeks().remove(companyCode);
        } else {
            settings.getCompanyTtmWeeks().put(companyCode, weeks);
        }
        return ResponseEntity.ok(userSettingsRepository.save(settings));
    }
}
