package com.personal.stocktracker.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * An admin reply embedded inside a {@link Message} thread.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageReply {

    // Username of the admin who replied.
    private String fromUsername;

    private String content;

    private LocalDateTime createdAt;
}
