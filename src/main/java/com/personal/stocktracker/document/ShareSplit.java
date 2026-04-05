package com.personal.stocktracker.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "share_splits")
public class ShareSplit {

    @Id
    private String id;

    @Indexed
    private String userId;

    private String companyCode;

    private LocalDate date;

    // e.g. subdivision 1:10 -> fromShares=1, toShares=10
    // e.g. merge 10:1 -> fromShares=10, toShares=1
    private Integer fromShares;
    private Integer toShares;

    private String type; // SUBDIVISION or MERGE

    private LocalDateTime createdAt;
}
