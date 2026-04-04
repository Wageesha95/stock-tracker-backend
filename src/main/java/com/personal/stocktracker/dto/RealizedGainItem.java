package com.personal.stocktracker.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RealizedGainItem {

    private String companyCode;
    private String companyName;
    private String logoUrl;
    private LocalDate sellDate;
    private Integer sharesSold;
    private BigDecimal avgBuyPrice;
    private BigDecimal sellPrice;
    private BigDecimal commission;
    private BigDecimal realizedGain;
    private BigDecimal gainPercent;
}
