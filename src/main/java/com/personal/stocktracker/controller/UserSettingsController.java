package com.personal.stocktracker.controller;

import com.personal.stocktracker.document.UserSettings;
import com.personal.stocktracker.repository.UserSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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
}
