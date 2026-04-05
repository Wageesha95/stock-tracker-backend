package com.personal.stocktracker.controller;

import com.personal.stocktracker.document.Broker;
import com.personal.stocktracker.repository.BrokerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/brokers")
@RequiredArgsConstructor
public class BrokerController {

    private final BrokerRepository brokerRepository;

    @GetMapping
    public ResponseEntity<List<Broker>> getAll() {
        return ResponseEntity.ok(brokerRepository.findAllByOrderByNameAsc());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, String> body) {
        String name = body.get("name");
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Broker name is required"));
        }
        if (brokerRepository.existsByName(name.trim())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Broker already exists"));
        }
        Broker broker = Broker.builder()
                .name(name.trim())
                .createdAt(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.CREATED).body(brokerRepository.save(broker));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        brokerRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
