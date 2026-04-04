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
@Document(collection = "watchlists")
public class Watchlist {

    @Id
    private String id;

    @Indexed
    private String userId;

    private String name;

    private String color;

    @Builder.Default
    private List<String> companyCodes = new ArrayList<>();

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
