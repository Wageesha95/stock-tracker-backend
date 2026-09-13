package com.personal.stocktracker.controller;

import com.personal.stocktracker.document.Company;
import com.personal.stocktracker.document.IndustryGroup;
import com.personal.stocktracker.document.MarketData;
import com.personal.stocktracker.document.ShareSplit;
import com.personal.stocktracker.document.Transaction;
import com.personal.stocktracker.document.TransactionType;
import com.personal.stocktracker.document.UserSettings;
import com.personal.stocktracker.dto.PortfolioItem;
import com.personal.stocktracker.dto.RealizedGainItem;
import com.personal.stocktracker.repository.CompanyRepository;
import com.personal.stocktracker.repository.IndustryGroupRepository;
import com.personal.stocktracker.repository.MarketDataRepository;
import com.personal.stocktracker.repository.ShareSplitRepository;
import com.personal.stocktracker.repository.TransactionRepository;
import com.personal.stocktracker.repository.UserSettingsRepository;
import com.personal.stocktracker.util.TransactionComparators;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final UserSettingsRepository userSettingsRepository;
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
    public ResponseEntity<Map<String, Object>> getAll(@RequestParam(required = false) List<String> brokers) {
        long start = System.currentTimeMillis();
        String username = SecurityContextHolder.getContext().getAuthentication().getName();

        long t0 = System.currentTimeMillis();
        List<Transaction> rawTransactions = transactionRepository.findByUserIdOrderByDateDesc(username);
        // Wasted rights: a disabled ".R" holding that was NOT converted to shares. The
        // money paid for the lapsed right is a realized loss (handled separately below).
        List<Transaction> wastedTransactions = rawTransactions.stream()
                .filter(tx -> Boolean.TRUE.equals(tx.getDisabled()) && !Boolean.TRUE.equals(tx.getConverted()))
                .collect(Collectors.toList());
        // Disabled transactions (converted ".R" rights or wasted ones) never count in the
        // live portfolio/gain calculations.
        List<Transaction> allTransactions = rawTransactions.stream()
                .filter(tx -> tx.getDisabled() == null || !tx.getDisabled())
                .collect(Collectors.toList());
        // Optional broker data filter. Empty/absent = all. "__none__" includes manual (no-broker) trades.
        if (brokers != null && !brokers.isEmpty()) {
            boolean includeNone = brokers.contains("__none__");
            java.util.function.Predicate<Transaction> brokerMatch = tx ->
                    (tx.getBrokerId() != null && brokers.contains(tx.getBrokerId()))
                    || (tx.getBrokerId() == null && includeNone);
            allTransactions = allTransactions.stream().filter(brokerMatch).collect(Collectors.toList());
            wastedTransactions = wastedTransactions.stream().filter(brokerMatch).collect(Collectors.toList());
        }
        log.info("Dashboard [{}] transactions: {}ms ({} records)", username, System.currentTimeMillis() - t0, allTransactions.size());

        t0 = System.currentTimeMillis();
        List<ShareSplit> allSplits = shareSplitRepository.findAllByOrderByDateDesc();
        log.info("Dashboard [{}] splits: {}ms ({} records)", username, System.currentTimeMillis() - t0, allSplits.size());

        t0 = System.currentTimeMillis();
        Map<String, MarketData> marketDataMap = marketDataService.getLatestPerCompany().stream()
                .collect(Collectors.toMap(MarketData::getCompanyCode, m -> m, (a, b) -> a));
        Map<String, BigDecimal> previousCloseMap = marketDataService.getPreviousClosePerCompany();
        log.info("Dashboard [{}] marketData: {}ms ({} companies)", username, System.currentTimeMillis() - t0, marketDataMap.size());

        t0 = System.currentTimeMillis();
        Map<String, Company> companyMap = companyRepository.findAll().stream()
                .collect(Collectors.toMap(Company::getCode, c -> c, (a, b) -> a));
        log.info("Dashboard [{}] companies: {}ms ({} records)", username, System.currentTimeMillis() - t0, companyMap.size());

        Map<String, List<Transaction>> grouped = allTransactions.stream()
                .collect(Collectors.groupingBy(Transaction::getCompanyCode));

        LocalDate today = LocalDate.now();

        // --- Portfolio ---
        t0 = System.currentTimeMillis();
        List<PortfolioItem> portfolio = new ArrayList<>();
        for (Map.Entry<String, List<Transaction>> entry : grouped.entrySet()) {
            String code = entry.getKey();
            List<Transaction> txns = new ArrayList<>(entry.getValue());
            txns.sort(TransactionComparators.BY_DATE_BUYS_FIRST);

            MarketData md = marketDataMap.get(code);
            BigDecimal lastTrade = md != null ? md.getLastTrade() : BigDecimal.ZERO;
            String companyName = md != null ? md.getCompanyName() : code;
            LocalDate mdDate = md != null ? md.getTradeDate() : null;

            // Day change measured against the previous trading day's close (standard day gain).
            // Falls back to the stored intraday change (close - open) when there is no prior day.
            BigDecimal prevClose = previousCloseMap.get(code);
            BigDecimal change;
            BigDecimal changePercent;
            if (prevClose != null && prevClose.compareTo(BigDecimal.ZERO) != 0) {
                change = lastTrade.subtract(prevClose);
                changePercent = change.divide(prevClose, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP);
            } else {
                // CSE-sourced rows may have null change/change% (no previous close) — default to ZERO
                // so the multiply/setScale below never NPEs.
                change = md != null && md.getChange() != null ? md.getChange() : BigDecimal.ZERO;
                changePercent = md != null && md.getChangePercent() != null ? md.getChangePercent() : BigDecimal.ZERO;
            }

            // FIFO running calculation
            int sharesHeld = 0;
            BigDecimal costBasis = BigDecimal.ZERO;
            BigDecimal realizedGain = BigDecimal.ZERO;

            // Day-gain accounting: shares acquired on the latest trade date moved from their
            // own buy price to the close, not over the full open->close range (they were
            // bought intraday), so they must not get the whole day's change applied to them.
            int sharesBoughtOnMdDate = 0;
            BigDecimal dayGainFromTodayBuys = BigDecimal.ZERO;

            for (Transaction tx : txns) {
                int adjCount = adjustCount(tx.getCount(), tx.getPrice(), code, tx.getDate(), allSplits);
                BigDecimal adjPrice = adjustPrice(tx.getPrice(), code, tx.getDate(), allSplits);

                if (tx.getType().isAcquisition()) {
                    sharesHeld += adjCount;
                    costBasis = costBasis.add(adjPrice.multiply(BigDecimal.valueOf(adjCount)).add(tx.getCommission()));
                    if (mdDate != null && mdDate.equals(tx.getDate())) {
                        sharesBoughtOnMdDate += adjCount;
                        dayGainFromTodayBuys = dayGainFromTodayBuys.add(
                                lastTrade.subtract(adjPrice).multiply(BigDecimal.valueOf(adjCount)));
                    }
                } else if (tx.getType() == TransactionType.TRANSFER_OUT) {
                    // Not a disposal: removes exactly the cost its own price represents,
                    // mirroring what the matching TRANSFER_IN adds at the other broker.
                    costBasis = costBasis.subtract(adjPrice.multiply(BigDecimal.valueOf(adjCount)));
                    sharesHeld -= adjCount;
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

            BigDecimal sharesHeldBd = BigDecimal.valueOf(sharesHeld);
            BigDecimal currentValue = sharesHeldBd.multiply(lastTrade);
            BigDecimal totalInvested = sharesHeldBd.multiply(avgBuyPrice);
            BigDecimal unrealizedGain = currentValue.subtract(totalInvested);
            BigDecimal unrealizedGainPercent = totalInvested.compareTo(BigDecimal.ZERO) != 0
                    ? unrealizedGain.divide(totalInvested, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            // Shares already held going into the trade day move prev-close->close (= change per
            // share); shares bought during that day move buy-price->close (dayGainFromTodayBuys).
            int sharesHeldBeforeMdDate = Math.max(sharesHeld - sharesBoughtOnMdDate, 0);
            BigDecimal unrealizedDayGain = BigDecimal.valueOf(sharesHeldBeforeMdDate).multiply(change)
                    .add(dayGainFromTodayBuys)
                    .setScale(4, RoundingMode.HALF_UP);

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
            txns.sort(TransactionComparators.BY_DATE_BUYS_FIRST);

            Company comp = companyMap.get(code);
            String compName = comp != null ? comp.getName() : code;

            int buyShares = 0;
            BigDecimal buyCost = BigDecimal.ZERO;

            for (Transaction tx : txns) {
                // Normalize to post-split basis so a pre-split buy and a post-split sell
                // are measured in the same share units (otherwise the gain is nonsense).
                int adjCount = adjustCount(tx.getCount(), tx.getPrice(), code, tx.getDate(), allSplits);
                BigDecimal adjPrice = adjustPrice(tx.getPrice(), code, tx.getDate(), allSplits);

                if (tx.getType().isAcquisition()) {
                    buyShares += adjCount;
                    buyCost = buyCost.add(adjPrice.multiply(BigDecimal.valueOf(adjCount)).add(tx.getCommission()));
                } else if (tx.getType() == TransactionType.TRANSFER_OUT) {
                    // Moving brokers realizes nothing, so it contributes no realized-gain row.
                    buyCost = buyCost.subtract(adjPrice.multiply(BigDecimal.valueOf(adjCount)));
                    buyShares -= adjCount;
                } else if (tx.getType() == TransactionType.SELL) {
                    BigDecimal avg = buyShares > 0 ? buyCost.divide(BigDecimal.valueOf(buyShares), 4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
                    BigDecimal sellRev = adjPrice.multiply(BigDecimal.valueOf(adjCount)).subtract(tx.getCommission());
                    BigDecimal costBasis = avg.multiply(BigDecimal.valueOf(adjCount));
                    BigDecimal gain = sellRev.subtract(costBasis).setScale(4, RoundingMode.HALF_UP);
                    BigDecimal gainPct = costBasis.compareTo(BigDecimal.ZERO) != 0
                            ? gain.divide(costBasis, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO;

                    realizedItems.add(RealizedGainItem.builder()
                            .companyCode(code).companyName(compName)
                            .sellDate(tx.getDate()).sharesSold(adjCount)
                            .avgBuyPrice(avg).sellPrice(adjPrice).commission(tx.getCommission())
                            .realizedGain(gain).gainPercent(gainPct).build());

                    buyShares -= adjCount;
                    buyCost = buyCost.subtract(costBasis);
                }
            }
        }
        // Wasted rights: for each lapsed (disabled, non-converted) ".R" holding, the net
        // money paid for the rights that were never exercised is booked as a realized loss.
        Map<String, List<Transaction>> wastedByCode = wastedTransactions.stream()
                .collect(Collectors.groupingBy(Transaction::getCompanyCode));
        for (Map.Entry<String, List<Transaction>> entry : wastedByCode.entrySet()) {
            String code = entry.getKey();
            List<Transaction> txns = new ArrayList<>(entry.getValue());
            txns.sort(TransactionComparators.BY_DATE_BUYS_FIRST);

            int shares = 0, bought = 0;
            BigDecimal cost = BigDecimal.ZERO;
            LocalDate lastDate = null;
            for (Transaction tx : txns) {
                if (tx.getType() == TransactionType.SELL) {
                    shares -= tx.getCount();
                } else if (tx.getType() == TransactionType.TRANSFER_OUT) {
                    // Transferred away before it lapsed — those shares, and the money paid
                    // for them, belong to the receiving broker, not to this lapsed lot.
                    shares -= tx.getCount();
                    bought -= tx.getCount();
                    cost = cost.subtract(tx.getPrice().multiply(BigDecimal.valueOf(tx.getCount())));
                } else {
                    shares += tx.getCount();
                    bought += tx.getCount();
                    cost = cost.add(tx.getPrice().multiply(BigDecimal.valueOf(tx.getCount())).add(tx.getCommission()));
                }
                if (lastDate == null || tx.getDate().isAfter(lastDate)) lastDate = tx.getDate();
            }
            if (shares <= 0) continue; // fully sold on the market — nothing lapsed

            BigDecimal avg = bought > 0 ? cost.divide(BigDecimal.valueOf(bought), 4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            BigDecimal lossBasis = avg.multiply(BigDecimal.valueOf(shares)).setScale(4, RoundingMode.HALF_UP);
            Company comp = companyMap.get(code);
            realizedItems.add(RealizedGainItem.builder()
                    .companyCode(code)
                    .companyName(comp != null ? comp.getName() : code)
                    .sellDate(lastDate != null ? lastDate : today)
                    .sharesSold(shares)
                    .avgBuyPrice(avg)
                    .sellPrice(BigDecimal.ZERO)
                    .commission(BigDecimal.ZERO)
                    .realizedGain(lossBasis.negate())
                    .gainPercent(BigDecimal.valueOf(-100))
                    .note("Purchased the right but didn't convert to a share")
                    .build());
        }

        realizedItems.sort((a, b) -> b.getSellDate().compareTo(a.getSellDate()));
        log.info("Dashboard [{}] realized calc: {}ms ({} items)", username, System.currentTimeMillis() - t0, realizedItems.size());

        // --- Opportunity Cost (per-lot FIFO) ---
        // Each buy-lot accrues interest on its actual purchase cost for exactly
        // the time it was held: from buy date until consumed by a SELL (oldest
        // lots first), or until today while still held. The breakdown response
        // exposes one entry per buy lot so the UI can show real lot detail.
        t0 = System.currentTimeMillis();
        List<Transaction> chronologicalTx = new ArrayList<>(allTransactions);
        chronologicalTx.sort(TransactionComparators.BY_DATE_BUYS_FIRST);

        // Lot record (mutable). originalShares/buyDate stay fixed; remaining,
        // endDate and accruedInterest evolve as time passes and SELLs consume.
        class Lot {
            final String code;
            final LocalDate buyDate;
            final double originalShares;
            final double costPerShare;
            double remaining;
            LocalDate endDate;        // null while still held
            String status;            // "held", "sold", "partial"
            double accruedInterest;
            Lot(String code, LocalDate buyDate, double shares, double costPerShare) {
                this.code = code;
                this.buyDate = buyDate;
                this.originalShares = shares;
                this.costPerShare = costPerShare;
                this.remaining = shares;
                this.status = "held";
            }
        }

        Map<String, Deque<Lot>> activeLots = new LinkedHashMap<>(); // open queue per company
        Map<String, List<Lot>> allLotsByCode = new LinkedHashMap<>(); // history (open + closed)
        double totalInterestD = 0.0;
        LocalDate lastAccrual = null;

        // The rate the user considers their money could have earned elsewhere.
        double opportunityCostRatePct = userSettingsRepository.findByUserId(username)
                .map(UserSettings::getOpportunityCostRate)
                .filter(Objects::nonNull)
                .orElse(UserSettings.DEFAULT_OPPORTUNITY_COST_RATE);
        double annualRate = opportunityCostRatePct / 100.0;

        for (Transaction tx : chronologicalTx) {
            if (lastAccrual != null) {
                long periodDays = ChronoUnit.DAYS.between(lastAccrual, tx.getDate());
                if (periodDays > 0) {
                    for (Deque<Lot> lots : activeLots.values()) {
                        for (Lot lot : lots) {
                            double inc = lot.remaining * lot.costPerShare * annualRate * periodDays / 365.0;
                            lot.accruedInterest += inc;
                            totalInterestD += inc;
                        }
                    }
                }
            }
            lastAccrual = tx.getDate();

            String code = tx.getCompanyCode();
            Deque<Lot> openQueue = activeLots.computeIfAbsent(code, k -> new ArrayDeque<>());
            List<Lot> history = allLotsByCode.computeIfAbsent(code, k -> new ArrayList<>());

            // Post-split basis so buy lots and sell quantities match in the same units.
            int adjCount = adjustCount(tx.getCount(), tx.getPrice(), code, tx.getDate(), allSplits);
            BigDecimal adjPrice = adjustPrice(tx.getPrice(), code, tx.getDate(), allSplits);

            if (tx.getType().isAcquisition()) {
                if (adjCount > 0) {
                    double lotCost = adjPrice.doubleValue() * adjCount + tx.getCommission().doubleValue();
                    Lot lot = new Lot(code, tx.getDate(), adjCount, lotCost / adjCount);
                    openQueue.addLast(lot);
                    history.add(lot);
                }
            } else if (tx.getType().isDisposal()) {
                // Both legs of a transfer are walked rather than skipped: under an active
                // broker filter only one leg is in scope, so skipping would leave the lots
                // unbalanced. The trade-off is that an unfiltered view restarts the
                // interest-accrual clock for transferred shares on the TRANSFER_IN date.
                boolean transferred = tx.getType() == TransactionType.TRANSFER_OUT;
                double toSell = adjCount;
                while (toSell > 0 && !openQueue.isEmpty()) {
                    Lot lot = openQueue.peekFirst();
                    if (lot.remaining <= toSell) {
                        toSell -= lot.remaining;
                        lot.remaining = 0;
                        lot.endDate = tx.getDate();
                        lot.status = transferred ? "transferred" : "sold";
                        openQueue.pollFirst();
                    } else {
                        lot.remaining -= toSell;
                        lot.status = "partial";
                        // partial fills do not close the lot; it continues to accrue
                        toSell = 0;
                    }
                }
            }
        }

        // Final accrual: from last transaction up to today, on still-open lots.
        if (lastAccrual != null) {
            long remainingDays = ChronoUnit.DAYS.between(lastAccrual, today);
            if (remainingDays > 0) {
                for (Deque<Lot> lots : activeLots.values()) {
                    for (Lot lot : lots) {
                        double inc = lot.remaining * lot.costPerShare * annualRate * remainingDays / 365.0;
                        lot.accruedInterest += inc;
                        totalInterestD += inc;
                    }
                }
            }
        }

        BigDecimal totalInterest = BigDecimal.valueOf(totalInterestD).setScale(4, RoundingMode.HALF_UP);

        // Build breakdown — one row per company, with per-lot children.
        List<Map<String, Object>> breakdown = new ArrayList<>();
        for (Map.Entry<String, List<Lot>> entry : allLotsByCode.entrySet()) {
            String code = entry.getKey();
            List<Lot> lots = entry.getValue();
            if (lots.isEmpty()) continue;

            double currentCostD = 0.0;
            double companyInterestD = 0.0;
            for (Lot lot : lots) {
                currentCostD += lot.remaining * lot.costPerShare;
                companyInterestD += lot.accruedInterest;
            }
            // Skip companies fully sold with negligible interest.
            if (currentCostD <= 0 && companyInterestD < 0.005) continue;

            Company comp = companyMap.get(code);
            LocalDate firstDate = lots.get(0).buyDate;
            long days = ChronoUnit.DAYS.between(firstDate, today);

            List<Map<String, Object>> lotJson = new ArrayList<>();
            for (Lot lot : lots) {
                LocalDate endD = lot.endDate != null ? lot.endDate : today;
                long lotDays = ChronoUnit.DAYS.between(lot.buyDate, endD);
                double lotCostD = lot.originalShares * lot.costPerShare;

                Map<String, Object> j = new LinkedHashMap<>();
                j.put("buyDate", lot.buyDate.toString());
                j.put("shares", lot.originalShares);
                j.put("remaining", lot.remaining);
                j.put("costPerShare", BigDecimal.valueOf(lot.costPerShare).setScale(4, RoundingMode.HALF_UP));
                j.put("lotCost", BigDecimal.valueOf(lotCostD).setScale(4, RoundingMode.HALF_UP));
                j.put("status", lot.status);
                j.put("endDate", lot.endDate != null ? lot.endDate.toString() : null);
                j.put("days", lotDays);
                j.put("interest", BigDecimal.valueOf(lot.accruedInterest).setScale(2, RoundingMode.HALF_UP));
                lotJson.add(j);
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("companyCode", code);
            item.put("companyName", comp != null ? comp.getName() : code);
            item.put("date", firstDate.toString());
            item.put("amount", BigDecimal.valueOf(currentCostD).setScale(4, RoundingMode.HALF_UP));
            item.put("days", days);
            item.put("interest", BigDecimal.valueOf(companyInterestD).setScale(2, RoundingMode.HALF_UP));
            item.put("lots", lotJson);
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
        result.put("bankInterestRate", opportunityCostRatePct);
        result.put("sectors", sectors);
        return ResponseEntity.ok(result);
    }
}
