package com.personal.stocktracker.controller;

import com.personal.stocktracker.document.LoginHistory;
import com.personal.stocktracker.document.User;
import com.personal.stocktracker.repository.LoginHistoryRepository;
import com.personal.stocktracker.repository.TransactionRepository;
import com.personal.stocktracker.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;
    private final LoginHistoryRepository loginHistoryRepository;
    private final PasswordEncoder passwordEncoder;

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        List<User> users = userRepository.findAll();

        Map<String, Long> txCountByUser = transactionRepository.findAll().stream()
                .filter(t -> t.getUserId() != null)
                .collect(Collectors.groupingBy(t -> t.getUserId(), Collectors.counting()));

        List<Map<String, Object>> userStats = new ArrayList<>();
        for (User user : users) {
            Map<String, Object> stat = new LinkedHashMap<>();
            stat.put("id", user.getId());
            stat.put("username", user.getUsername());
            stat.put("role", user.getRole());
            stat.put("transactionCount", txCountByUser.getOrDefault(user.getUsername(), 0L));
            stat.put("locked", user.isLocked());
            stat.put("createdAt", user.getCreatedAt() != null ? user.getCreatedAt().toString() : null);
            userStats.add(stat);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalUsers", users.size());
        result.put("users", userStats);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/users")
    public ResponseEntity<?> createUser(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        String role = body.getOrDefault("role", "USER");

        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username and password required"));
        }

        if (userRepository.findByUsername(username).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Username already exists"));
        }

        String readPassword = body.get("readPassword");

        User user = User.builder()
                .username(username)
                .password(passwordEncoder.encode(password))
                .readPassword(readPassword != null && !readPassword.isBlank() ? passwordEncoder.encode(readPassword) : null)
                .role(role.toUpperCase())
                .createdAt(LocalDateTime.now())
                .build();

        userRepository.save(user);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "id", user.getId(), "username", user.getUsername(), "role", user.getRole()
        ));
    }

    @PutMapping("/users/{id}")
    public ResponseEntity<?> updateUser(@PathVariable String id, @RequestBody Map<String, String> body) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found: " + id));

        if (body.containsKey("username") && !body.get("username").isBlank()) {
            String newUsername = body.get("username");
            if (!newUsername.equals(user.getUsername()) && userRepository.findByUsername(newUsername).isPresent()) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Username already exists"));
            }
            user.setUsername(newUsername);
        }

        if (body.containsKey("password") && !body.get("password").isBlank()) {
            user.setPassword(passwordEncoder.encode(body.get("password")));
        }

        if (body.containsKey("readPassword") && !body.get("readPassword").isBlank()) {
            user.setReadPassword(passwordEncoder.encode(body.get("readPassword")));
        }

        if (body.containsKey("role") && !body.get("role").isBlank()) {
            user.setRole(body.get("role").toUpperCase());
        }

        userRepository.save(user);
        return ResponseEntity.ok(Map.of(
                "id", user.getId(), "username", user.getUsername(), "role", user.getRole()
        ));
    }

    @PutMapping("/users/{id}/unlock")
    public ResponseEntity<?> unlockUser(@PathVariable String id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found: " + id));
        user.setLocked(false);
        user.setFailedAttempts(0);
        userRepository.save(user);
        return ResponseEntity.ok(Map.of("message", "User " + user.getUsername() + " unlocked"));
    }

    @GetMapping("/login-history")
    public ResponseEntity<List<LoginHistory>> getLoginHistory() {
        // Auto-delete records older than 10 days
        loginHistoryRepository.deleteByTimestampBefore(LocalDateTime.now().minusDays(10));
        return ResponseEntity.ok(loginHistoryRepository.findAllByOrderByTimestampDesc());
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<?> deleteUser(@PathVariable String id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found: " + id));

        if ("ADMIN".equals(user.getRole())) {
            long adminCount = userRepository.findAll().stream()
                    .filter(u -> "ADMIN".equals(u.getRole())).count();
            if (adminCount <= 1) {
                return ResponseEntity.badRequest().body(Map.of("error", "Cannot delete the last admin"));
            }
        }

        userRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
