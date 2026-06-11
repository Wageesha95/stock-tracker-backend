package com.personal.stocktracker.controller;

import com.personal.stocktracker.document.Message;
import com.personal.stocktracker.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * User-facing messaging: lets any authenticated user send a message to the
 * admin and review the messages they have sent.
 */
@RestController
@RequestMapping("/api/messages")
@RequiredArgsConstructor
public class MessageController {

    private static final int MAX_CONTENT_LENGTH = 2000;

    private final MessageRepository messageRepository;

    private String currentUsername() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    @PostMapping
    public ResponseEntity<?> send(@RequestBody Map<String, String> body) {
        String content = body.get("content");
        if (content == null || content.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Message content is required"));
        }
        String trimmed = content.trim();
        if (trimmed.length() > MAX_CONTENT_LENGTH) {
            trimmed = trimmed.substring(0, MAX_CONTENT_LENGTH);
        }
        Message message = Message.builder()
                .fromUsername(currentUsername())
                .content(trimmed)
                .read(false)
                .createdAt(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.CREATED).body(messageRepository.save(message));
    }

    @GetMapping("/mine")
    public ResponseEntity<List<Message>> mine() {
        return ResponseEntity.ok(messageRepository.findByFromUsernameOrderByCreatedAtDesc(currentUsername()));
    }

    @GetMapping("/unread-reply-count")
    public ResponseEntity<Map<String, Long>> unreadReplyCount() {
        long count = messageRepository.findByFromUsernameOrderByCreatedAtDesc(currentUsername()).stream()
                .filter(m -> !m.isUserRead() && m.getReplies() != null && !m.getReplies().isEmpty())
                .count();
        return ResponseEntity.ok(Map.of("count", count));
    }

    @PutMapping("/read-replies")
    public ResponseEntity<Void> markRepliesRead() {
        List<Message> messages = messageRepository.findByFromUsernameOrderByCreatedAtDesc(currentUsername());
        List<Message> changed = messages.stream().filter(m -> !m.isUserRead()).toList();
        if (!changed.isEmpty()) {
            changed.forEach(m -> m.setUserRead(true));
            messageRepository.saveAll(changed);
        }
        return ResponseEntity.noContent().build();
    }
}
