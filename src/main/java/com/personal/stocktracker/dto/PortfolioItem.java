package com.personal.stocktracker.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortfolioItem {

    private String companyCode;
    private String companyName;
    private String logoUrl;
    private Integer sharesHeld;
    private BigDecimal avgBuyPrice;
    private BigDecimal lastTrade;
    private BigDecimal change;
    private BigDecimal changePercent;
    private BigDecimal currentValue;
    private BigDecimal totalInvested;
    private BigDecimal unrealizedGain;
    private BigDecimal unrealizedGainPercent;
    private BigDecimal unrealizedDayGain;
    private BigDecimal realizedGain;
}
