package com.personal.stocktracker.dto;

import com.personal.stocktracker.document.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransactionRequest {

    @NotBlank(message = "Company code is required")
    private String companyCode;

    @NotNull(message = "Date is required")
    private LocalDate date;

    @NotNull(message = "Transaction type is required")
    private TransactionType type;

    @NotNull(message = "Share count is required")
    private Integer count;

    @NotNull(message = "Price is required")
    private BigDecimal price;

    @NotNull(message = "Commission is required")
    private BigDecimal commission;

    // Broker this trade went through. Optional: null for manually-entered
    // transactions that are not attributed to any broker.
    private String brokerId;
}
