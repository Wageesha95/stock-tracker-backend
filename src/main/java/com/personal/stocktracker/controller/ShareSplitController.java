package com.personal.stocktracker.controller;

import com.personal.stocktracker.document.ShareSplit;
import com.personal.stocktracker.repository.ShareSplitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Share splits / merges are global corporate-action facts, shared across all users
 * (not scoped to a userId). Managed by admins from the Admin Dashboard.
 */
@RestController
@RequestMapping("/api/share-splits")
@RequiredArgsConstructor
public class ShareSplitController {

    private final ShareSplitRepository shareSplitRepository;

    @GetMapping
    public ResponseEntity<List<ShareSplit>> getAll() {
        return ResponseEntity.ok(shareSplitRepository.findAllByOrderByDateDesc());
    }

    @PostMapping
    public ResponseEntity<ShareSplit> create(@RequestBody Map<String, Object> body) {
        String companyCode = ((String) body.get("companyCode")).toUpperCase();
        String dateStr = (String) body.get("date");
        int fromShares = ((Number) body.get("fromShares")).intValue();
        int toShares = ((Number) body.get("toShares")).intValue();
        String type = toShares > fromShares ? "SUBDIVISION" : "MERGE";

        ShareSplit split = ShareSplit.builder()
                .companyCode(companyCode)
                .date(java.time.LocalDate.parse(dateStr))
                .fromShares(fromShares)
                .toShares(toShares)
                .type(type)
                .createdAt(LocalDateTime.now())
                .build();

        return ResponseEntity.status(HttpStatus.CREATED).body(shareSplitRepository.save(split));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ShareSplit> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        ShareSplit split = shareSplitRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Share split not found: " + id));

        split.setDate(java.time.LocalDate.parse((String) body.get("date")));
        split.setFromShares(((Number) body.get("fromShares")).intValue());
        split.setToShares(((Number) body.get("toShares")).intValue());
        split.setType(split.getToShares() > split.getFromShares() ? "SUBDIVISION" : "MERGE");

        return ResponseEntity.ok(shareSplitRepository.save(split));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        if (!shareSplitRepository.existsById(id)) {
            throw new RuntimeException("Share split not found: " + id);
        }
        shareSplitRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
