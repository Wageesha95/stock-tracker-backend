package com.personal.stocktracker.controller;

import com.personal.stocktracker.config.JwtUtil;
import com.personal.stocktracker.document.User;
import com.personal.stocktracker.dto.AuthResponse;
import com.personal.stocktracker.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;

    private static final int MAX_FAILED_ATTEMPTS = 3;

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");

        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid credentials"));
        }

        if (user.isLocked()) {
            return ResponseEntity.status(423).body(Map.of("error", "Account is locked. Contact an admin to unlock."));
        }

        boolean readMode = false;
        boolean authenticated = false;

        // Check read password first
        if (user.getReadPassword() != null && passwordEncoder.matches(password, user.getReadPassword())) {
            readMode = true;
            authenticated = true;
        } else {
            // Try normal password via AuthenticationManager
            try {
                authenticationManager.authenticate(
                        new UsernamePasswordAuthenticationToken(username, password)
                );
                authenticated = true;
            } catch (Exception e) {
                // Authentication failed
            }
        }

        if (!authenticated) {
            user.setFailedAttempts(user.getFailedAttempts() + 1);
            if (user.getFailedAttempts() >= MAX_FAILED_ATTEMPTS) {
                user.setLocked(true);
                userRepository.save(user);
                return ResponseEntity.status(423).body(Map.of("error", "Account is locked after " + MAX_FAILED_ATTEMPTS + " failed attempts. Contact an admin to unlock."));
            }
            userRepository.save(user);
            int remaining = MAX_FAILED_ATTEMPTS - user.getFailedAttempts();
            return ResponseEntity.status(401).body(Map.of("error", "Invalid credentials. " + remaining + " attempt(s) remaining."));
        }

        // Reset failed attempts on successful login
        if (user.getFailedAttempts() > 0) {
            user.setFailedAttempts(0);
            userRepository.save(user);
        }

        String token = jwtUtil.generateToken(user.getUsername(), user.getRole(), readMode);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", user.getId());
        response.put("username", user.getUsername());
        response.put("role", user.getRole());
        response.put("readMode", readMode);
        response.put("token", token);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/me")
    public ResponseEntity<?> me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }

        String username = auth.getName();
        User user = userRepository.findByUsername(username).orElseThrow();

        return ResponseEntity.ok(AuthResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .role(user.getRole())
                .build());
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout() {
        return ResponseEntity.ok(Map.of("message", "Logged out"));
    }
}
