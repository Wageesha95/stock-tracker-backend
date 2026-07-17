package com.personal.stocktracker.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "rights")
public class Rights {

    @Id
    private String id;

    @Indexed
    private String userId;

    private String companyCode;

    private LocalDate date;

    private Integer count;

    private BigDecimal price;

    private String brokerId;

    private String transactionId;

    private LocalDateTime createdAt;
}
