package com.personal.stocktracker.controller;

import com.personal.stocktracker.document.Watchlist;
import com.personal.stocktracker.repository.WatchlistRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/watchlists")
@RequiredArgsConstructor
public class WatchlistController {

    private final WatchlistRepository watchlistRepository;

    private String currentUsername() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    private Watchlist getOwnedWatchlist(String id) {
        Watchlist watchlist = watchlistRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Watchlist not found: " + id));
        if (!watchlist.getUserId().equals(currentUsername())) {
            throw new RuntimeException("Access denied");
        }
        return watchlist;
    }

    @GetMapping
    public ResponseEntity<List<Watchlist>> getAll() {
        return ResponseEntity.ok(watchlistRepository.findByUserIdOrderByCreatedAtAsc(currentUsername()));
    }

    @PostMapping
    public ResponseEntity<Watchlist> create(@RequestBody Map<String, String> body) {
        Watchlist watchlist = Watchlist.builder()
                .userId(currentUsername())
                .name(body.getOrDefault("name", "My Watchlist"))
                .color(body.getOrDefault("color", "#3182ce"))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.CREATED).body(watchlistRepository.save(watchlist));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Watchlist> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Watchlist watchlist = getOwnedWatchlist(id);

        if (body.containsKey("name")) {
            watchlist.setName((String) body.get("name"));
        }
        if (body.containsKey("color")) {
            watchlist.setColor((String) body.get("color"));
        }
        if (body.containsKey("companyCodes")) {
            @SuppressWarnings("unchecked")
            List<String> codes = (List<String>) body.get("companyCodes");
            watchlist.setCompanyCodes(codes);
        }

        watchlist.setUpdatedAt(LocalDateTime.now());
        return ResponseEntity.ok(watchlistRepository.save(watchlist));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        getOwnedWatchlist(id);
        watchlistRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/companies")
    public ResponseEntity<Watchlist> addCompany(@PathVariable String id, @RequestBody Map<String, String> body) {
        Watchlist watchlist = getOwnedWatchlist(id);
        String code = body.get("companyCode");
        if (code != null && !watchlist.getCompanyCodes().contains(code)) {
            watchlist.getCompanyCodes().add(code);
            watchlist.setUpdatedAt(LocalDateTime.now());
            watchlistRepository.save(watchlist);
        }
        return ResponseEntity.ok(watchlist);
    }

    @DeleteMapping("/{id}/companies/{code}")
    public ResponseEntity<Watchlist> removeCompany(@PathVariable String id, @PathVariable String code) {
        Watchlist watchlist = getOwnedWatchlist(id);
        watchlist.getCompanyCodes().remove(code);
        watchlist.setUpdatedAt(LocalDateTime.now());
        watchlistRepository.save(watchlist);
        return ResponseEntity.ok(watchlist);
    }
}
