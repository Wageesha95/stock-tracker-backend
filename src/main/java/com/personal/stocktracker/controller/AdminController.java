package com.personal.stocktracker.controller;

import com.personal.stocktracker.document.*;
import com.personal.stocktracker.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;
    private final LoginHistoryRepository loginHistoryRepository;
    private final CompanyRepository companyRepository;
    private final IndustryGroupRepository industryGroupRepository;
    private final MarketDataRepository marketDataRepository;
    private final PasswordEncoder passwordEncoder;
    private final DividendPayoutRepository dividendPayoutRepository;
    private final DividendRepository dividendRepository;
    private final MongoTemplate mongoTemplate;

    // ---- Rights (.R) records per user: list + enable/disable ----
    @GetMapping("/rights-records")
    public ResponseEntity<List<Map<String, Object>>> getRightsRecords() {
        Map<String, List<Transaction>> groups = new LinkedHashMap<>();
        for (Transaction t : transactionRepository.findAll()) {
            if (t.getCompanyCode() != null && t.getCompanyCode().contains(".R")) {
                groups.computeIfAbsent(t.getUserId() + "|" + t.getCompanyCode(), k -> new ArrayList<>()).add(t);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, List<Transaction>> e : groups.entrySet()) {
            List<Transaction> txns = e.getValue();
            String[] parts = e.getKey().split("\\|", 2);
            int shares = 0;
            for (Transaction t : txns) {
                int c = t.getCount() != null ? t.getCount() : 0;
                shares += t.getType().isDisposal() ? -c : c;
            }
            boolean disabled = txns.stream().allMatch(t -> Boolean.TRUE.equals(t.getDisabled()));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", parts[0]);
            m.put("companyCode", parts.length > 1 ? parts[1] : "");
            m.put("shares", shares);
            m.put("disabled", disabled);
            m.put("txCount", txns.size());
            out.add(m);
        }
        out.sort((a, b) -> {
            int c = ((String) a.get("userId")).compareTo((String) b.get("userId"));
            return c != 0 ? c : ((String) a.get("companyCode")).compareTo((String) b.get("companyCode"));
        });
        return ResponseEntity.ok(out);
    }

    @PutMapping("/rights-records/disabled")
    public ResponseEntity<Map<String, Object>> setRightsDisabled(
            @RequestParam String userId, @RequestParam String code, @RequestParam boolean value) {
        List<Transaction> txns = transactionRepository.findByUserIdAndCompanyCodeOrderByDateDesc(userId, code.toUpperCase());
        txns.forEach(t -> t.setDisabled(value));
        transactionRepository.saveAll(txns);
        return ResponseEntity.ok(Map.of("userId", userId, "companyCode", code.toUpperCase(), "disabled", value, "updated", txns.size()));
    }

    @GetMapping("/system-stats")
    public ResponseEntity<Map<String, Object>> getSystemStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("companies", mongoTemplate.getCollection("companies").countDocuments());
        stats.put("transactions", mongoTemplate.getCollection("transactions").countDocuments());
        stats.put("dividends", mongoTemplate.getCollection("dividends").countDocuments());
        stats.put("dividendPayouts", mongoTemplate.getCollection("dividend_payouts").countDocuments());
        stats.put("marketData", mongoTemplate.getCollection("market_data").countDocuments());
        stats.put("stockPrices", mongoTemplate.getCollection("stock_prices").countDocuments());
        stats.put("industryGroups", mongoTemplate.getCollection("industry_groups").countDocuments());
        stats.put("watchlists", mongoTemplate.getCollection("watchlists").countDocuments());
        stats.put("loginHistory", mongoTemplate.getCollection("login_history").countDocuments());

        // Latest market data date + distinct date count via single server-side aggregation
        org.springframework.data.mongodb.core.aggregation.Aggregation mdAgg = org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation(
                org.springframework.data.mongodb.core.aggregation.Aggregation.match(
                        org.springframework.data.mongodb.core.query.Criteria.where("tradeDate").ne(null)
                ),
                org.springframework.data.mongodb.core.aggregation.Aggregation.group("tradeDate"),
                org.springframework.data.mongodb.core.aggregation.Aggregation.sort(org.springframework.data.domain.Sort.Direction.DESC, "_id")
        );
        List<org.bson.Document> dateGroups = mongoTemplate.aggregate(mdAgg, "market_data", org.bson.Document.class).getMappedResults();
        String latestMdStr = null;
        if (!dateGroups.isEmpty()) {
            Object id = dateGroups.get(0).get("_id");
            if (id instanceof java.time.LocalDate ld) latestMdStr = ld.toString();
            else if (id instanceof java.util.Date dt) latestMdStr = dt.toInstant().atZone(java.time.ZoneId.of("Asia/Colombo")).toLocalDate().toString();
            else if (id != null) latestMdStr = id.toString();
        }
        stats.put("latestMarketDate", latestMdStr);
        stats.put("marketDataDates", (long) dateGroups.size());

        return ResponseEntity.ok(stats);
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        List<User> users = userRepository.findAll();

        Map<String, Long> txCountByUser = transactionRepository.findAll().stream()
                .filter(t -> t.getUserId() != null)
                .collect(Collectors.groupingBy(t -> t.getUserId(), Collectors.counting()));

        List<Map<String, Object>> userStats = new ArrayList<>();
        for (User user : users) {
            Map<String, Object> stat = new LinkedHashMap<>();
            stat.put("id", user.getId());
            stat.put("username", user.getUsername());
            stat.put("role", user.getRole());
            stat.put("transactionCount", txCountByUser.getOrDefault(user.getUsername(), 0L));
            stat.put("locked", user.isLocked());
            stat.put("dividendPayoutsEnabled", user.isDividendPayoutsEnabled());
            stat.put("createdAt", user.getCreatedAt() != null ? user.getCreatedAt().toString() : null);
            userStats.add(stat);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalUsers", users.size());
        result.put("users", userStats);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/sector-distribution")
    public ResponseEntity<List<Map<String, Object>>> getSectorDistribution() {
        Map<String, Company> companyMap = companyRepository.findAll().stream()
                .collect(Collectors.toMap(Company::getCode, c -> c, (a, b) -> a));
        Map<String, IndustryGroup> groupMap = industryGroupRepository.findAll().stream()
                .collect(Collectors.toMap(IndustryGroup::getId, g -> g, (a, b) -> a));
        Map<String, MarketData> marketDataMap = marketDataRepository.findAll().stream()
                .collect(Collectors.toMap(MarketData::getCompanyCode, m -> m,
                        (a, b) -> a.getTradeDate() != null && b.getTradeDate() != null
                                && a.getTradeDate().isAfter(b.getTradeDate()) ? a : b));

        List<Transaction> allTx = transactionRepository.findAll();
        // Group by user
        Map<String, List<Transaction>> txByUser = allTx.stream()
                .filter(t -> t.getUserId() != null)
                .collect(Collectors.groupingBy(Transaction::getUserId));

        // For each user, compute shares held per company, then value per sector
        // Result: sector -> { sector, user1: value, user2: value, ... }
        Set<String> allUsers = new TreeSet<>();
        // sector -> user -> value
        Map<String, Map<String, java.math.BigDecimal>> sectorUserValue = new LinkedHashMap<>();
        // sector -> user -> list of {code, value}
        Map<String, Map<String, List<Map.Entry<String, java.math.BigDecimal>>>> sectorUserCompanies = new LinkedHashMap<>();

        for (var entry : txByUser.entrySet()) {
            String userId = entry.getKey();
            allUsers.add(userId);

            Map<String, Integer> sharesHeld = new LinkedHashMap<>();
            Map<String, List<Transaction>> byCompany = entry.getValue().stream()
                    .collect(Collectors.groupingBy(Transaction::getCompanyCode));

            for (var ce : byCompany.entrySet()) {
                String code = ce.getKey();
                List<Transaction> txns = new ArrayList<>(ce.getValue());
                txns.sort(Comparator.comparing(Transaction::getDate));
                int shares = 0;
                for (Transaction tx : txns) {
                    if (tx.getType().isAcquisition()) {
                        shares += tx.getCount();
                    } else if (tx.getType().isDisposal()) {
                        shares -= tx.getCount();
                    }
                }
                if (shares > 0) sharesHeld.put(code, shares);
            }

            for (var sh : sharesHeld.entrySet()) {
                String code = sh.getKey();
                int shares = sh.getValue();
                MarketData md = marketDataMap.get(code);
                java.math.BigDecimal price = md != null ? md.getLastTrade() : java.math.BigDecimal.ZERO;
                java.math.BigDecimal value = price.multiply(java.math.BigDecimal.valueOf(shares));

                Company comp = companyMap.get(code);
                String sectorName = "Uncategorized";
                if (comp != null && comp.getIndustryGroupId() != null) {
                    IndustryGroup grp = groupMap.get(comp.getIndustryGroupId());
                    if (grp != null) sectorName = grp.getName();
                }

                sectorUserValue.computeIfAbsent(sectorName, k -> new LinkedHashMap<>())
                        .merge(userId, value, java.math.BigDecimal::add);
                sectorUserCompanies.computeIfAbsent(sectorName, k -> new LinkedHashMap<>())
                        .computeIfAbsent(userId, k -> new ArrayList<>())
                        .add(Map.entry(code, value));
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (var entry : sectorUserValue.entrySet()) {
            String sector = entry.getKey();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sector", sector);
            for (String user : allUsers) {
                java.math.BigDecimal val = entry.getValue().getOrDefault(user, java.math.BigDecimal.ZERO);
                row.put(user, val.setScale(2, java.math.RoundingMode.HALF_UP));

                // Top 3 companies by value for this user in this sector
                var companies = sectorUserCompanies.getOrDefault(sector, Map.of()).getOrDefault(user, List.of());
                List<String> top3 = companies.stream()
                        .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                        .limit(3)
                        .map(e -> {
                            java.math.BigDecimal pct = val.compareTo(java.math.BigDecimal.ZERO) != 0
                                    ? e.getValue().multiply(java.math.BigDecimal.valueOf(100))
                                        .divide(val, 1, java.math.RoundingMode.HALF_UP)
                                    : java.math.BigDecimal.ZERO;
                            return e.getKey() + " " + pct + "%";
                        })
                        .collect(Collectors.toList());
                row.put(user + "_top3", top3);
            }
            result.add(row);
        }
        result.sort((a, b) -> {
            double aTotal = allUsers.stream().mapToDouble(u -> ((Number) a.getOrDefault(u, 0)).doubleValue()).sum();
            double bTotal = allUsers.stream().mapToDouble(u -> ((Number) b.getOrDefault(u, 0)).doubleValue()).sum();
            return Double.compare(bTotal, aTotal);
        });

        return ResponseEntity.ok(result);
    }

    @PostMapping("/users")
    public ResponseEntity<?> createUser(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        String role = body.getOrDefault("role", "USER");

        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username and password required"));
        }

        if (userRepository.findByUsername(username).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Username already exists"));
        }

        String readPassword = body.get("readPassword");

        User user = User.builder()
                .username(username)
                .password(passwordEncoder.encode(password))
                .readPassword(readPassword != null && !readPassword.isBlank() ? passwordEncoder.encode(readPassword) : null)
                .role(role.toUpperCase())
                .createdAt(LocalDateTime.now())
                .build();

        userRepository.save(user);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "id", user.getId(), "username", user.getUsername(), "role", user.getRole()
        ));
    }

    @PutMapping("/users/{id}")
    public ResponseEntity<?> updateUser(@PathVariable String id, @RequestBody Map<String, String> body) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found: " + id));

        if (body.containsKey("username") && !body.get("username").isBlank()) {
            String newUsername = body.get("username");
            if (!newUsername.equals(user.getUsername()) && userRepository.findByUsername(newUsername).isPresent()) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Username already exists"));
            }
            user.setUsername(newUsername);
        }

        if (body.containsKey("password") && !body.get("password").isBlank()) {
            user.setPassword(passwordEncoder.encode(body.get("password")));
        }

        if (body.containsKey("readPassword") && !body.get("readPassword").isBlank()) {
            user.setReadPassword(passwordEncoder.encode(body.get("readPassword")));
        }

        if (body.containsKey("role") && !body.get("role").isBlank()) {
            user.setRole(body.get("role").toUpperCase());
        }

        userRepository.save(user);
        return ResponseEntity.ok(Map.of(
                "id", user.getId(), "username", user.getUsername(), "role", user.getRole()
        ));
    }

    @PutMapping("/users/{id}/dividend-payouts")
    public ResponseEntity<?> toggleDividendPayouts(@PathVariable String id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found: " + id));
        user.setDividendPayoutsEnabled(!user.isDividendPayoutsEnabled());
        userRepository.save(user);
        return ResponseEntity.ok(Map.of(
                "username", user.getUsername(),
                "dividendPayoutsEnabled", user.isDividendPayoutsEnabled()
        ));
    }

    @PutMapping("/users/{id}/unlock")
    public ResponseEntity<?> unlockUser(@PathVariable String id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found: " + id));
        user.setLocked(false);
        user.setFailedAttempts(0);
        userRepository.save(user);
        return ResponseEntity.ok(Map.of("message", "User " + user.getUsername() + " unlocked"));
    }

    @GetMapping("/login-history")
    public ResponseEntity<List<LoginHistory>> getLoginHistory() {
        // Auto-delete records older than 10 days
        loginHistoryRepository.deleteByTimestampBefore(LocalDateTime.now().minusDays(10));
        return ResponseEntity.ok(loginHistoryRepository.findAllByOrderByTimestampDesc());
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<?> deleteUser(@PathVariable String id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found: " + id));

        if ("ADMIN".equals(user.getRole())) {
            long adminCount = userRepository.findAll().stream()
                    .filter(u -> "ADMIN".equals(u.getRole())).count();
            if (adminCount <= 1) {
                return ResponseEntity.badRequest().body(Map.of("error", "Cannot delete the last admin"));
            }
        }

        userRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
