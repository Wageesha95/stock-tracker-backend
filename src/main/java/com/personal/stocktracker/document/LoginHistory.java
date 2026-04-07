package com.personal.stocktracker.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "login_history")
public class LoginHistory {

    @Id
    private String id;

    private String username;

    private String action; // LOGIN, LOGOUT

    private String device;

    private String ipAddress;

    private boolean readMode;

    private LocalDateTime timestamp;
}
