package com.personal.stocktracker.controller;

import com.personal.stocktracker.config.JwtUtil;
import com.personal.stocktracker.document.LoginHistory;
import com.personal.stocktracker.document.User;
import com.personal.stocktracker.dto.AuthResponse;
import com.personal.stocktracker.repository.LoginHistoryRepository;
import com.personal.stocktracker.repository.UserRepository;
import com.personal.stocktracker.service.GeoIpService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final LoginHistoryRepository loginHistoryRepository;
    private final GeoIpService geoIpService;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;

    private static final int MAX_FAILED_ATTEMPTS = 3;

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body, HttpServletRequest request) {
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
            // Record failed login (async)
            String ua = request.getHeader("User-Agent");
            String failIp = request.getHeader("X-Forwarded-For");
            if (failIp == null || failIp.isBlank()) failIp = request.getRemoteAddr();
            final String fIp = failIp;
            final String fUa = ua;
            final String fUser = user.getUsername();
            CompletableFuture.runAsync(() -> loginHistoryRepository.save(LoginHistory.builder()
                    .username(fUser).action("FAILED_LOGIN")
                    .device(fUa != null ? fUa : "Unknown").ipAddress(fIp)
                    .location(geoIpService.lookup(fIp)).timestamp(LocalDateTime.now())
                    .build()));

            user.setFailedAttempts(user.getFailedAttempts() + 1);
            if (user.getFailedAttempts() >= MAX_FAILED_ATTEMPTS) {
                user.setLocked(true);
                userRepository.save(user);
                return ResponseEntity.status(423).body(Map.of("error", "Account is locked after " + MAX_FAILED_ATTEMPTS + " failed attempts. Contact an admin to unlock."));
            }
            userRepository.save(user);
            return ResponseEntity.status(401).body(Map.of("error", "Invalid credentials"));
        }

        // Reset failed attempts on successful login
        if (user.getFailedAttempts() > 0) {
            user.setFailedAttempts(0);
            userRepository.save(user);
        }

        // Record login (async)
        String userAgent = request.getHeader("User-Agent");
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) ip = request.getRemoteAddr();
        final String loginIp = ip;
        final String loginUa = userAgent;
        final String loginUser = user.getUsername();
        final boolean loginReadMode = readMode;
        CompletableFuture.runAsync(() -> loginHistoryRepository.save(LoginHistory.builder()
                .username(loginUser).action("LOGIN")
                .device(loginUa != null ? loginUa : "Unknown").ipAddress(loginIp)
                .location(geoIpService.lookup(loginIp)).readMode(loginReadMode)
                .timestamp(LocalDateTime.now())
                .build()));

        String token = jwtUtil.generateToken(user.getUsername(), user.getRole(), readMode);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", user.getId());
        response.put("username", user.getUsername());
        response.put("role", user.getRole());
        response.put("readMode", readMode);
        response.put("dividendPayoutsEnabled", user.isDividendPayoutsEnabled());
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
                .dividendPayoutsEnabled(user.isDividendPayoutsEnabled())
                .build());
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            String userAgent = request.getHeader("User-Agent");
            String ip = request.getHeader("X-Forwarded-For");
            if (ip == null || ip.isBlank()) ip = request.getRemoteAddr();
            final String logoutIp = ip;
            final String logoutUa = userAgent;
            final String logoutUser = auth.getName();
            CompletableFuture.runAsync(() -> loginHistoryRepository.save(LoginHistory.builder()
                    .username(logoutUser)
                    .action("LOGOUT")
                    .device(logoutUa != null ? logoutUa : "Unknown")
                    .ipAddress(logoutIp)
                    .location(geoIpService.lookup(logoutIp))
                    .timestamp(LocalDateTime.now())
                    .build()));
        }
        return ResponseEntity.ok(Map.of("message", "Logged out"));
    }
}
