package com.personal.stocktracker.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

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

    private LocalDateTime createdAt;

    private LocalDateTime readAt;
}
