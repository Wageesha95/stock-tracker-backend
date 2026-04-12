package com.personal.stocktracker.controller;

import com.personal.stocktracker.document.Company;
import com.personal.stocktracker.document.IndustryGroup;
import com.personal.stocktracker.document.MarketData;
import com.personal.stocktracker.document.ShareSplit;
import com.personal.stocktracker.document.Transaction;
import com.personal.stocktracker.document.TransactionType;
import com.personal.stocktracker.dto.PortfolioItem;
import com.personal.stocktracker.dto.RealizedGainItem;
import com.personal.stocktracker.repository.CompanyRepository;
import com.personal.stocktracker.repository.IndustryGroupRepository;
import com.personal.stocktracker.repository.MarketDataRepository;
import com.personal.stocktracker.repository.ShareSplitRepository;
import com.personal.stocktracker.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final TransactionRepository transactionRepository;
    private final MarketDataRepository marketDataRepository;
    private final CompanyRepository companyRepository;
    private final IndustryGroupRepository industryGroupRepository;
    private final ShareSplitRepository shareSplitRepository;
    private final com.personal.stocktracker.service.MarketDataService marketDataService;

    /**
     * Apply split adjustments to a transaction's count and price.
     * For each split that happened AFTER this transaction, multiply count by ratio and divide price.
     */
    private int adjustCount(int count, BigDecimal price, String companyCode, java.time.LocalDate txDate, List<ShareSplit> splits) {
        int adjusted = count;
        for (ShareSplit s : splits) {
            if (s.getCompanyCode().equals(companyCode) && s.getDate().isAfter(txDate)) {
                adjusted = (int) Math.round((double) adjusted * s.getToShares() / s.getFromShares());
            }
        }
        return adjusted;
    }

    private BigDecimal adjustPrice(BigDecimal price, String companyCode, java.time.LocalDate txDate, List<ShareSplit> splits) {
        BigDecimal adjusted = price;
        for (ShareSplit s : splits) {
            if (s.getCompanyCode().equals(companyCode) && s.getDate().isAfter(txDate)) {
                adjusted = adjusted.multiply(BigDecimal.valueOf(s.getFromShares()))
                        .divide(BigDecimal.valueOf(s.getToShares()), 4, RoundingMode.HALF_UP);
            }
        }
        return adjusted;
    }

    @GetMapping("/all")
    public ResponseEntity<Map<String, Object>> getAll() {
        long start = System.currentTimeMillis();
        String username = SecurityContextHolder.getContext().getAuthentication().getName();

        long t0 = System.currentTimeMillis();
        List<Transaction> allTransactions = transactionRepository.findByUserIdOrderByDateDesc(username);
        log.info("Dashboard [{}] transactions: {}ms ({} records)", username, System.currentTimeMillis() - t0, allTransactions.size());

        t0 = System.currentTimeMillis();
        List<ShareSplit> allSplits = shareSplitRepository.findByUserIdOrderByDateDesc(username);
        log.info("Dashboard [{}] splits: {}ms ({} records)", username, System.currentTimeMillis() - t0, allSplits.size());

        t0 = System.currentTimeMillis();
        Map<String, MarketData> marketDataMap = marketDataService.getLatestPerCompany().stream()
                .collect(Collectors.toMap(MarketData::getCompanyCode, m -> m, (a, b) -> a));
        log.info("Dashboard [{}] marketData: {}ms ({} companies)", username, System.currentTimeMillis() - t0, marketDataMap.size());

        t0 = System.currentTimeMillis();
        Map<String, Company> companyMap = companyRepository.findAll().stream()
                .collect(Collectors.toMap(Company::getCode, c -> c, (a, b) -> a));
        log.info("Dashboard [{}] companies: {}ms ({} records)", username, System.currentTimeMillis() - t0, companyMap.size());

        Map<String, List<Transaction>> grouped = allTransactions.stream()
                .collect(Collectors.groupingBy(Transaction::getCompanyCode));

        LocalDate today = LocalDate.now();
        BigDecimal annualRate = new BigDecimal("0.065");

        // --- Portfolio ---
        t0 = System.currentTimeMillis();
        List<PortfolioItem> portfolio = new ArrayList<>();
        for (Map.Entry<String, List<Transaction>> entry : grouped.entrySet()) {
            String code = entry.getKey();
            List<Transaction> txns = new ArrayList<>(entry.getValue());
            txns.sort(Comparator.comparing(Transaction::getDate));

            // FIFO running calculation
            int sharesHeld = 0;
            BigDecimal costBasis = BigDecimal.ZERO;
            BigDecimal realizedGain = BigDecimal.ZERO;

            for (Transaction tx : txns) {
                int adjCount = adjustCount(tx.getCount(), tx.getPrice(), code, tx.getDate(), allSplits);
                BigDecimal adjPrice = adjustPrice(tx.getPrice(), code, tx.getDate(), allSplits);

                if (tx.getType() == TransactionType.BUY || tx.getType() == TransactionType.RIGHTS || tx.getType() == TransactionType.SCRIP_DIVIDEND || tx.getType() == TransactionType.IPO) {
                    sharesHeld += adjCount;
                    costBasis = costBasis.add(adjPrice.multiply(BigDecimal.valueOf(adjCount)).add(tx.getCommission()));
                } else if (tx.getType() == TransactionType.SELL) {
                    BigDecimal avgAtSell = sharesHeld > 0
                            ? costBasis.divide(BigDecimal.valueOf(sharesHeld), 4, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO;
                    BigDecimal costRemoved = avgAtSell.multiply(BigDecimal.valueOf(adjCount));
                    BigDecimal sellRevenue = adjPrice.multiply(BigDecimal.valueOf(adjCount)).subtract(tx.getCommission());
                    realizedGain = realizedGain.add(sellRevenue.subtract(costRemoved));
                    costBasis = costBasis.subtract(costRemoved);
                    sharesHeld -= adjCount;
                }
            }

            sharesHeld = Math.max(sharesHeld, 0);
            BigDecimal avgBuyPrice = sharesHeld > 0
                    ? costBasis.divide(BigDecimal.valueOf(sharesHeld), 4, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            realizedGain = realizedGain.setScale(4, RoundingMode.HALF_UP);

            MarketData md = marketDataMap.get(code);
            BigDecimal lastTrade = md != null ? md.getLastTrade() : BigDecimal.ZERO;
            BigDecimal change = md != null ? md.getChange() : BigDecimal.ZERO;
            BigDecimal changePercent = md != null ? md.getChangePercent() : BigDecimal.ZERO;
            String companyName = md != null ? md.getCompanyName() : code;

            BigDecimal sharesHeldBd = BigDecimal.valueOf(sharesHeld);
            BigDecimal currentValue = sharesHeldBd.multiply(lastTrade);
            BigDecimal totalInvested = sharesHeldBd.multiply(avgBuyPrice);
            BigDecimal unrealizedGain = currentValue.subtract(totalInvested);
            BigDecimal unrealizedGainPercent = totalInvested.compareTo(BigDecimal.ZERO) != 0
                    ? unrealizedGain.divide(totalInvested, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            BigDecimal unrealizedDayGain = currentValue.multiply(changePercent).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);

            portfolio.add(PortfolioItem.builder()
                    .companyCode(code).companyName(companyName)
                    .sharesHeld(sharesHeld).avgBuyPrice(avgBuyPrice)
                    .lastTrade(lastTrade).change(change).changePercent(changePercent)
                    .currentValue(currentValue).totalInvested(totalInvested)
                    .unrealizedGain(unrealizedGain).unrealizedGainPercent(unrealizedGainPercent)
                    .unrealizedDayGain(unrealizedDayGain).realizedGain(realizedGain)
                    .build());
        }

        log.info("Dashboard [{}] portfolio calc: {}ms ({} items)", username, System.currentTimeMillis() - t0, portfolio.size());

        // --- Realized Gains ---
        t0 = System.currentTimeMillis();
        List<RealizedGainItem> realizedItems = new ArrayList<>();
        for (Map.Entry<String, List<Transaction>> entry : grouped.entrySet()) {
            String code = entry.getKey();
            List<Transaction> txns = new ArrayList<>(entry.getValue());
            txns.sort(Comparator.comparing(Transaction::getDate));

            Company comp = companyMap.get(code);
            String compName = comp != null ? comp.getName() : code;

            int buyShares = 0;
            BigDecimal buyCost = BigDecimal.ZERO;

            for (Transaction tx : txns) {
                if (tx.getType() == TransactionType.BUY || tx.getType() == TransactionType.RIGHTS || tx.getType() == TransactionType.SCRIP_DIVIDEND || tx.getType() == TransactionType.IPO) {
                    buyShares += tx.getCount();
                    buyCost = buyCost.add(tx.getPrice().multiply(BigDecimal.valueOf(tx.getCount())).add(tx.getCommission()));
                } else if (tx.getType() == TransactionType.SELL) {
                    BigDecimal avg = buyShares > 0 ? buyCost.divide(BigDecimal.valueOf(buyShares), 4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
                    BigDecimal sellRev = tx.getPrice().multiply(BigDecimal.valueOf(tx.getCount())).subtract(tx.getCommission());
                    BigDecimal costBasis = avg.multiply(BigDecimal.valueOf(tx.getCount()));
                    BigDecimal gain = sellRev.subtract(costBasis).setScale(4, RoundingMode.HALF_UP);
                    BigDecimal gainPct = costBasis.compareTo(BigDecimal.ZERO) != 0
                            ? gain.divide(costBasis, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO;

                    realizedItems.add(RealizedGainItem.builder()
                            .companyCode(code).companyName(compName)
                            .sellDate(tx.getDate()).sharesSold(tx.getCount())
                            .avgBuyPrice(avg).sellPrice(tx.getPrice()).commission(tx.getCommission())
                            .realizedGain(gain).gainPercent(gainPct).build());

                    buyShares -= tx.getCount();
                    buyCost = buyCost.subtract(costBasis);
                }
            }
        }
        realizedItems.sort((a, b) -> b.getSellDate().compareTo(a.getSellDate()));
        log.info("Dashboard [{}] realized calc: {}ms ({} items)", username, System.currentTimeMillis() - t0, realizedItems.size());

        // --- Opportunity Cost (FIFO running balance) ---
        t0 = System.currentTimeMillis();
        // Sort all transactions chronologically
        List<Transaction> chronologicalTx = new ArrayList<>(allTransactions);
        chronologicalTx.sort(Comparator.comparing(Transaction::getDate));

        BigDecimal totalInterest = BigDecimal.ZERO;
        List<Map<String, Object>> breakdown = new ArrayList<>();

        // Track running cost basis per company (FIFO)
        Map<String, BigDecimal> companyCostBasis = new LinkedHashMap<>();
        Map<String, Integer> companyShares = new LinkedHashMap<>();

        // Calculate interest on running balance between each transaction date
        BigDecimal runningTotalCost = BigDecimal.ZERO;
        LocalDate lastDate = null;

        for (Transaction tx : chronologicalTx) {
            // Calculate interest for the period since last transaction
            if (lastDate != null && runningTotalCost.compareTo(BigDecimal.ZERO) > 0) {
                long periodDays = ChronoUnit.DAYS.between(lastDate, tx.getDate());
                if (periodDays > 0) {
                    BigDecimal periodInterest = runningTotalCost.multiply(annualRate)
                            .multiply(BigDecimal.valueOf(periodDays))
                            .divide(BigDecimal.valueOf(365), 4, RoundingMode.HALF_UP);
                    totalInterest = totalInterest.add(periodInterest);
                }
            }

            String code = tx.getCompanyCode();
            BigDecimal cb = companyCostBasis.getOrDefault(code, BigDecimal.ZERO);
            int shares = companyShares.getOrDefault(code, 0);

            if (tx.getType() == TransactionType.BUY || tx.getType() == TransactionType.RIGHTS || tx.getType() == TransactionType.SCRIP_DIVIDEND || tx.getType() == TransactionType.IPO) {
                BigDecimal amount = tx.getPrice().multiply(BigDecimal.valueOf(tx.getCount())).add(tx.getCommission());
                cb = cb.add(amount);
                shares += tx.getCount();
                runningTotalCost = runningTotalCost.add(amount);
            } else if (tx.getType() == TransactionType.SELL) {
                BigDecimal avgAtSell = shares > 0
                        ? cb.divide(BigDecimal.valueOf(shares), 4, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
                BigDecimal costRemoved = avgAtSell.multiply(BigDecimal.valueOf(tx.getCount()));
                cb = cb.subtract(costRemoved);
                shares -= tx.getCount();
                runningTotalCost = runningTotalCost.subtract(costRemoved);
            }

            companyCostBasis.put(code, cb);
            companyShares.put(code, shares);
            lastDate = tx.getDate();
        }

        // Interest from last transaction to today
        if (lastDate != null && runningTotalCost.compareTo(BigDecimal.ZERO) > 0) {
            long remainingDays = ChronoUnit.DAYS.between(lastDate, today);
            if (remainingDays > 0) {
                BigDecimal remainingInterest = runningTotalCost.multiply(annualRate)
                        .multiply(BigDecimal.valueOf(remainingDays))
                        .divide(BigDecimal.valueOf(365), 4, RoundingMode.HALF_UP);
                totalInterest = totalInterest.add(remainingInterest);
            }
        }

        // Build breakdown per company (current invested amount and total days)
        for (Map.Entry<String, BigDecimal> entry : companyCostBasis.entrySet()) {
            String code = entry.getKey();
            BigDecimal currentCost = entry.getValue();
            if (currentCost.compareTo(BigDecimal.ZERO) <= 0) continue;

            Company comp = companyMap.get(code);
            // Find first buy date for this company
            LocalDate firstDate = chronologicalTx.stream()
                    .filter(t -> t.getCompanyCode().equals(code))
                    .map(Transaction::getDate)
                    .findFirst().orElse(today);
            long days = ChronoUnit.DAYS.between(firstDate, today);

            BigDecimal interest = currentCost.multiply(annualRate)
                    .multiply(BigDecimal.valueOf(days))
                    .divide(BigDecimal.valueOf(365), 2, RoundingMode.HALF_UP);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("companyCode", code);
            item.put("companyName", comp != null ? comp.getName() : code);
            item.put("date", firstDate.toString());
            item.put("amount", currentCost.setScale(4, RoundingMode.HALF_UP));
            item.put("days", days);
            item.put("interest", interest);
            breakdown.add(item);
        }
        breakdown.sort((a, b) -> ((BigDecimal) b.get("interest")).compareTo((BigDecimal) a.get("interest")));
        log.info("Dashboard [{}] opportunity cost calc: {}ms", username, System.currentTimeMillis() - t0);

        // --- Sector Summary ---
        t0 = System.currentTimeMillis();
        Map<String, IndustryGroup> groupMap = industryGroupRepository.findAll().stream()
                .collect(Collectors.toMap(IndustryGroup::getId, g -> g, (a, b) -> a));

        // Group portfolio items (with shares > 0) by sector
        Map<String, List<Map<String, Object>>> sectorData = new LinkedHashMap<>();
        for (PortfolioItem item : portfolio) {
            if (item.getSharesHeld() <= 0) continue;
            Company comp = companyMap.get(item.getCompanyCode());
            String sectorName = "Uncategorized";
            if (comp != null && comp.getIndustryGroupId() != null) {
                IndustryGroup grp = groupMap.get(comp.getIndustryGroupId());
                if (grp != null) sectorName = grp.getName();
            }
            sectorData.computeIfAbsent(sectorName, k -> new ArrayList<>());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("companyCode", item.getCompanyCode());
            entry.put("companyName", item.getCompanyName());
            entry.put("sharesHeld", item.getSharesHeld());
            entry.put("currentValue", item.getCurrentValue());
            entry.put("totalInvested", item.getTotalInvested());
            entry.put("unrealizedGain", item.getUnrealizedGain());
            entry.put("unrealizedGainPercent", item.getUnrealizedGainPercent());
            entry.put("unrealizedDayGain", item.getUnrealizedDayGain());
            entry.put("changePercent", item.getChangePercent());
            sectorData.get(sectorName).add(entry);
        }

        List<Map<String, Object>> sectors = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : sectorData.entrySet()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("sector", e.getKey());
            s.put("companies", e.getValue());
            BigDecimal sectorValue = e.getValue().stream()
                    .map(c -> (BigDecimal) c.get("currentValue"))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal sectorInvested = e.getValue().stream()
                    .map(c -> (BigDecimal) c.get("totalInvested"))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            s.put("currentValue", sectorValue.setScale(4, RoundingMode.HALF_UP));
            s.put("totalInvested", sectorInvested.setScale(4, RoundingMode.HALF_UP));
            s.put("unrealizedGain", sectorValue.subtract(sectorInvested).setScale(4, RoundingMode.HALF_UP));
            BigDecimal sectorDayGain = e.getValue().stream()
                    .map(c -> (BigDecimal) c.get("unrealizedDayGain"))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            s.put("unrealizedDayGain", sectorDayGain.setScale(4, RoundingMode.HALF_UP));
            s.put("companyCount", e.getValue().size());
            sectors.add(s);
        }
        sectors.sort((a, b) -> ((BigDecimal) b.get("currentValue")).compareTo((BigDecimal) a.get("currentValue")));

        log.info("Dashboard [{}] sector calc: {}ms", username, System.currentTimeMillis() - t0);
        log.info("Dashboard [{}] TOTAL: {}ms", username, System.currentTimeMillis() - start);

        // --- Response ---
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("portfolio", portfolio);
        result.put("realizedItems", realizedItems);
        result.put("opportunityCost", totalInterest.setScale(4, RoundingMode.HALF_UP));
        result.put("interestBreakdown", breakdown);
        result.put("bankInterestRate", 6.5);
        result.put("sectors", sectors);
        return ResponseEntity.ok(result);
    }
}
