package com.personal.stocktracker.controller;

import com.personal.stocktracker.document.User;
import com.personal.stocktracker.repository.TransactionRepository;
import com.personal.stocktracker.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        List<User> users = userRepository.findAll();

        Map<String, Long> txCountByUser = transactionRepository.findAll().stream()
                .filter(t -> t.getUserId() != null)
                .collect(Collectors.groupingBy(t -> t.getUserId(), Collectors.counting()));

        List<Map<String, Object>> userStats = new ArrayList<>();
        for (User user : users) {
            Map<String, Object> stat = new LinkedHashMap<>();
            stat.put("username", user.getUsername());
            stat.put("role", user.getRole());
            stat.put("transactionCount", txCountByUser.getOrDefault(user.getUsername(), 0L));
            userStats.add(stat);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalUsers", users.size());
        result.put("users", userStats);
        return ResponseEntity.ok(result);
    }
}
