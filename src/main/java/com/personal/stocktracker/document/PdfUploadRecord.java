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
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "pdf_uploads")
public class PdfUploadRecord {

    @Id
    private String id;

    @Indexed
    private String userId;

    private String filename;

    @Indexed(unique = true)
    private LocalDate tradeDate;

    private List<String> transactionIds;

    private int transactionCount;

    private LocalDateTime uploadedAt;
}
