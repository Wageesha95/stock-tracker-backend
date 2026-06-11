package com.personal.stocktracker.controller;

import com.personal.stocktracker.document.Message;
import com.personal.stocktracker.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Admin message inbox. Secured to ADMIN by the {@code /api/admin/**} rule in
 * SecurityConfig.
 */
@RestController
@RequestMapping("/api/admin/messages")
@RequiredArgsConstructor
public class AdminMessageController {

    private final MessageRepository messageRepository;

    @GetMapping
    public ResponseEntity<List<Message>> all() {
        return ResponseEntity.ok(messageRepository.findAllByOrderByCreatedAtDesc());
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> unreadCount() {
        return ResponseEntity.ok(Map.of("count", messageRepository.countByReadFalse()));
    }

    @PutMapping("/{id}/read")
    public ResponseEntity<Message> markRead(@PathVariable String id) {
        Message message = messageRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Message not found: " + id));
        if (!message.isRead()) {
            message.setRead(true);
            message.setReadAt(LocalDateTime.now());
            messageRepository.save(message);
        }
        return ResponseEntity.ok(message);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        messageRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
