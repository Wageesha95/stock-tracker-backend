package com.personal.stocktracker.dto;

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
public class StockPriceRequest {

    @NotBlank(message = "Company code is required")
    private String companyCode;

    @NotNull(message = "Price is required")
    private BigDecimal price;

    @NotNull(message = "Date is required")
    private LocalDate date;
}
