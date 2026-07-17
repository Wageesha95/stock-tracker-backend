package com.personal.stocktracker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.personal.stocktracker.document.DividendType;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DividendRequest {

    @NotBlank(message = "Company code is required")
    private String companyCode;

    @NotNull(message = "Type is required")
    private DividendType type;

    private BigDecimal amount;

    @NotNull(message = "Date is required")
    private LocalDate date;

    private LocalDate xdDate;

    private Integer shares;

    private Integer scripShares;

    private BigDecimal totalAmount;

    private Boolean taxed;

    private String brokerId;
}
