package com.personal.stocktracker.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "messages")
public class Message {

    @Id
    private String id;

    // Username of the user who sent the message to the admin.
    @Indexed
    private String fromUsername;

    private String content;

    // Whether an admin has read the message.
    private boolean read;

    // Whether the user has seen the latest admin reply. True when there is
    // nothing new for the user to read.
    @Builder.Default
    private boolean userRead = true;

    // Admin replies in this thread, oldest first.
    @Builder.Default
    private List<MessageReply> replies = new ArrayList<>();

    private LocalDateTime createdAt;

    private LocalDateTime readAt;
}
