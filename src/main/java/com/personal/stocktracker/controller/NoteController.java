package com.personal.stocktracker.controller;

import com.personal.stocktracker.document.Note;
import com.personal.stocktracker.repository.NoteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notes")
@RequiredArgsConstructor
public class NoteController {

    private final NoteRepository noteRepository;

    private String currentUsername() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    private Note getOwnedNote(String id) {
        Note note = noteRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Note not found: " + id));
        if (!note.getUserId().equals(currentUsername())) {
            throw new RuntimeException("Access denied");
        }
        return note;
    }

    private static String normalizeKey(String key) {
        if (key == null) return "note";
        String trimmed = key.trim();
        return trimmed.isEmpty() ? "note" : trimmed;
    }

    @GetMapping
    public ResponseEntity<List<Note>> getAll() {
        return ResponseEntity.ok(noteRepository.findByUserIdOrderByUpdatedAtDesc(currentUsername()));
    }

    @GetMapping("/company/{code}")
    public ResponseEntity<List<Note>> getByCompany(@PathVariable String code) {
        return ResponseEntity.ok(noteRepository.findByUserIdAndCompanyCodeOrderByUpdatedAtDesc(currentUsername(), code.toUpperCase()));
    }

    @PostMapping
    public ResponseEntity<Note> create(@RequestBody Map<String, String> body) {
        String companyCode = body.get("companyCode");
        String value = body.get("value");
        if (companyCode == null || companyCode.isBlank() || value == null || value.isBlank()) {
            throw new RuntimeException("companyCode and value are required");
        }
        LocalDateTime now = LocalDateTime.now();
        Note note = Note.builder()
                .userId(currentUsername())
                .companyCode(companyCode.toUpperCase())
                .key(normalizeKey(body.get("key")))
                .value(value)
                .createdAt(now)
                .updatedAt(now)
                .build();
        return ResponseEntity.status(HttpStatus.CREATED).body(noteRepository.save(note));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Note> update(@PathVariable String id, @RequestBody Map<String, String> body) {
        Note note = getOwnedNote(id);
        if (body.containsKey("key")) {
            note.setKey(normalizeKey(body.get("key")));
        }
        if (body.containsKey("value")) {
            note.setValue(body.get("value"));
        }
        note.setUpdatedAt(LocalDateTime.now());
        return ResponseEntity.ok(noteRepository.save(note));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        getOwnedNote(id);
        noteRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
