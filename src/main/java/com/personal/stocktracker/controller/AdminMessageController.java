package com.personal.stocktracker.controller;

import com.personal.stocktracker.document.Message;
import com.personal.stocktracker.document.MessageReply;
import com.personal.stocktracker.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
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

    private static final int MAX_CONTENT_LENGTH = 2000;

    private final MessageRepository messageRepository;

    private String currentUsername() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

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

    @PostMapping("/{id}/reply")
    public ResponseEntity<?> reply(@PathVariable String id, @RequestBody Map<String, String> body) {
        String content = body.get("content");
        if (content == null || content.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Reply content is required"));
        }
        String trimmed = content.trim();
        if (trimmed.length() > MAX_CONTENT_LENGTH) {
            trimmed = trimmed.substring(0, MAX_CONTENT_LENGTH);
        }
        Message message = messageRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Message not found: " + id));

        LocalDateTime now = LocalDateTime.now();
        if (message.getReplies() == null) {
            message.setReplies(new ArrayList<>());
        }
        message.getReplies().add(MessageReply.builder()
                .fromUsername(currentUsername())
                .content(trimmed)
                .createdAt(now)
                .build());

        // Replying implies the admin has read it; the user now has an unread reply.
        message.setRead(true);
        if (message.getReadAt() == null) {
            message.setReadAt(now);
        }
        message.setUserRead(false);

        return ResponseEntity.ok(messageRepository.save(message));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        messageRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
