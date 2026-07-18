package com.personal.stocktracker.config;

import com.personal.stocktracker.document.Broker;
import com.personal.stocktracker.document.Dividend;
import com.personal.stocktracker.document.Ipo;
import com.personal.stocktracker.document.PdfUploadRecord;
import com.personal.stocktracker.document.Rights;
import com.personal.stocktracker.document.Transaction;
import com.personal.stocktracker.document.User;
import com.personal.stocktracker.repository.BrokerRepository;
import com.personal.stocktracker.repository.DividendRepository;
import com.personal.stocktracker.repository.IpoRepository;
import com.personal.stocktracker.repository.PdfUploadRecordRepository;
import com.personal.stocktracker.repository.RightsRepository;
import com.personal.stocktracker.repository.TransactionRepository;
import com.personal.stocktracker.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TransactionRepository transactionRepository;
    private final DividendRepository dividendRepository;
    private final PdfUploadRecordRepository pdfUploadRecordRepository;
    private final BrokerRepository brokerRepository;
    private final RightsRepository rightsRepository;
    private final IpoRepository ipoRepository;
    private final MongoTemplate mongoTemplate;

    @Override
    public void run(String... args) {
        seedUsers();
        unlockAdmins();
        seedBrokers();
        migrateExistingData();
    }

    private void seedUsers() {
        if (userRepository.findByUsername("imwageesha").isEmpty()) {
            userRepository.save(User.builder()
                    .username("imwageesha")
                    .password(passwordEncoder.encode("123123"))
                    .readPassword(passwordEncoder.encode("read123"))
                    .role("USER")
                    .createdAt(LocalDateTime.now())
                    .build());
            log.info("Created user: imwageesha");
        }

        if (userRepository.findByUsername("navod").isEmpty()) {
            userRepository.save(User.builder()
                    .username("navod")
                    .password(passwordEncoder.encode("dimsum"))
                    .readPassword(passwordEncoder.encode("readdimsum"))
                    .role("USER")
                    .createdAt(LocalDateTime.now())
                    .build());
            log.info("Created user: navod");
        }

        if (userRepository.findByUsername("admin").isEmpty()) {
            userRepository.save(User.builder()
                    .username("admin")
                    .password(passwordEncoder.encode("admin"))
                    .readPassword(passwordEncoder.encode("readadmin"))
                    .role("ADMIN")
                    .createdAt(LocalDateTime.now())
                    .build());
            log.info("Created user: admin");
        }

        // Backfill readPassword for existing users that don't have one
        Map<String, String> readPasswords = Map.of(
                "imwageesha", "read123",
                "navod", "readdimsum",
                "admin", "readadmin"
        );
        for (var entry : readPasswords.entrySet()) {
            userRepository.findByUsername(entry.getKey()).ifPresent(user -> {
                if (user.getReadPassword() == null) {
                    user.setReadPassword(passwordEncoder.encode(entry.getValue()));
                    userRepository.save(user);
                    log.info("Backfilled readPassword for user: {}", entry.getKey());
                }
            });
        }
    }

    private void unlockAdmins() {
        userRepository.findAll().stream()
                .filter(u -> "ADMIN".equals(u.getRole()) && u.isLocked())
                .forEach(u -> {
                    u.setLocked(false);
                    u.setFailedAttempts(0);
                    userRepository.save(u);
                    log.info("Auto-unlocked admin: {}", u.getUsername());
                });
    }

    private void seedBrokers() {
        for (String name : List.of("Softlogic", "Almas", "CAL")) {
            if (!brokerRepository.existsByName(name)) {
                brokerRepository.save(Broker.builder()
                        .name(name)
                        .createdAt(LocalDateTime.now())
                        .build());
                log.info("Created broker: {}", name);
            }
        }
    }

    private void migrateExistingData() {
        // Assign all existing transactions without userId to imwageesha
        List<Transaction> unownedTx = transactionRepository.findAll().stream()
                .filter(t -> t.getUserId() == null)
                .toList();
        if (!unownedTx.isEmpty()) {
            unownedTx.forEach(t -> t.setUserId("imwageesha"));
            transactionRepository.saveAll(unownedTx);
            log.info("Migrated {} transactions to user imwageesha", unownedTx.size());
        }

        // Assign all existing dividends without userId to imwageesha
        List<Dividend> unownedDiv = dividendRepository.findAll().stream()
                .filter(d -> d.getUserId() == null)
                .toList();
        if (!unownedDiv.isEmpty()) {
            unownedDiv.forEach(d -> d.setUserId("imwageesha"));
            dividendRepository.saveAll(unownedDiv);
            log.info("Migrated {} dividends to user imwageesha", unownedDiv.size());
        }

        // Assign all existing PDF uploads without userId to imwageesha
        List<PdfUploadRecord> unownedPdf = pdfUploadRecordRepository.findAll().stream()
                .filter(p -> p.getUserId() == null)
                .toList();
        if (!unownedPdf.isEmpty()) {
            unownedPdf.forEach(p -> p.setUserId("imwageesha"));
            pdfUploadRecordRepository.saveAll(unownedPdf);
            log.info("Migrated {} PDF uploads to user imwageesha", unownedPdf.size());
        }

        // Assign Softlogic broker to existing PDF uploads without brokerId
        Optional<Broker> softlogic = brokerRepository.findAllByOrderByNameAsc().stream()
                .filter(b -> "Softlogic".equals(b.getName()))
                .findFirst();
        if (softlogic.isPresent()) {
            List<PdfUploadRecord> noBroker = pdfUploadRecordRepository.findAll().stream()
                    .filter(p -> p.getBrokerId() == null)
                    .toList();
            if (!noBroker.isEmpty()) {
                // Drop old unique index on tradeDate before saving
                try {
                    mongoTemplate.getCollection("pdf_uploads").dropIndex("tradeDate_1");
                    log.info("Dropped old tradeDate_1 unique index");
                } catch (Exception e) {
                    // Index may not exist, ignore
                }
                noBroker.forEach(p -> p.setBrokerId(softlogic.get().getId()));
                pdfUploadRecordRepository.saveAll(noBroker);
                log.info("Migrated {} PDF uploads to broker Softlogic", noBroker.size());
            }
        }

        // Backfill Transaction.brokerId from the PDF upload records that produced them,
        // so the broker data filter has something to work with for historical trades.
        List<Transaction> allTx = transactionRepository.findAll();
        Map<String, Transaction> txById = allTx.stream()
                .collect(java.util.stream.Collectors.toMap(Transaction::getId, t -> t, (a, b) -> a));
        List<Transaction> toTag = new java.util.ArrayList<>();
        for (PdfUploadRecord rec : pdfUploadRecordRepository.findAll()) {
            if (rec.getBrokerId() == null || rec.getTransactionIds() == null) continue;
            for (String txId : rec.getTransactionIds()) {
                Transaction tx = txById.get(txId);
                if (tx != null && tx.getBrokerId() == null) {
                    tx.setBrokerId(rec.getBrokerId());
                    toTag.add(tx);
                }
            }
        }
        if (!toTag.isEmpty()) {
            transactionRepository.saveAll(toTag);
            log.info("Backfilled brokerId on {} transactions from PDF uploads", toTag.size());
        }

        // ONE-TIME backfill: attribute every existing dividend to Softlogic, so
        // historical dividends line up with the (Softlogic-backfilled) historical
        // trades. Guarded by a persisted flag so it never runs again — otherwise a
        // dividend legitimately saved with no broker later would be force-reassigned
        // to Softlogic on the next restart.
        final String DIV_BROKER_BACKFILL = "dividend-broker-softlogic-backfill";
        if (softlogic.isPresent() && !migrationDone(DIV_BROKER_BACKFILL)) {
            List<Dividend> noBrokerDiv = dividendRepository.findAll().stream()
                    .filter(d -> d.getBrokerId() == null)
                    .toList();
            if (!noBrokerDiv.isEmpty()) {
                noBrokerDiv.forEach(d -> d.setBrokerId(softlogic.get().getId()));
                dividendRepository.saveAll(noBrokerDiv);
                log.info("Migrated {} existing dividends to broker Softlogic (one-time)", noBrokerDiv.size());
            }
            markMigrationDone(DIV_BROKER_BACKFILL);
        }

        // ONE-TIME backfill: attribute existing rights & IPO records (and their linked
        // transactions) to Softlogic, so historical corporate-action holdings survive a
        // broker data filter. Guarded so it never re-runs.
        final String RI_BROKER_BACKFILL = "rights-ipo-broker-softlogic-backfill";
        if (softlogic.isPresent() && !migrationDone(RI_BROKER_BACKFILL)) {
            String sid = softlogic.get().getId();
            List<Transaction> tagged = new java.util.ArrayList<>();

            List<Rights> rNoBroker = rightsRepository.findAll().stream()
                    .filter(r -> r.getBrokerId() == null)
                    .toList();
            rNoBroker.forEach(r -> {
                r.setBrokerId(sid);
                if (r.getTransactionId() != null) {
                    transactionRepository.findById(r.getTransactionId()).ifPresent(tx -> {
                        if (tx.getBrokerId() == null) { tx.setBrokerId(sid); tagged.add(tx); }
                    });
                }
            });
            if (!rNoBroker.isEmpty()) rightsRepository.saveAll(rNoBroker);

            List<Ipo> iNoBroker = ipoRepository.findAll().stream()
                    .filter(i -> i.getBrokerId() == null)
                    .toList();
            iNoBroker.forEach(i -> {
                i.setBrokerId(sid);
                if (i.getTransactionId() != null) {
                    transactionRepository.findById(i.getTransactionId()).ifPresent(tx -> {
                        if (tx.getBrokerId() == null) { tx.setBrokerId(sid); tagged.add(tx); }
                    });
                }
            });
            if (!iNoBroker.isEmpty()) ipoRepository.saveAll(iNoBroker);

            if (!tagged.isEmpty()) transactionRepository.saveAll(tagged);
            log.info("Migrated {} rights and {} ipos to broker Softlogic (one-time)", rNoBroker.size(), iNoBroker.size());
            markMigrationDone(RI_BROKER_BACKFILL);
        }

        // ONE-TIME: previously, converting a ".R" right only "disabled" it. Now a disabled
        // ".R" is treated as WASTED (a realized loss) unless flagged converted. Mark every
        // already-disabled ".R" whose base ".N" has a rights issue as converted, so historical
        // conversions are not mistaken for lapsed rights and booked as losses.
        final String RIGHTS_CONVERTED_FLAG = "rights-converted-flag-backfill";
        if (!migrationDone(RIGHTS_CONVERTED_FLAG)) {
            java.util.Set<String> nWithRights = rightsRepository.findAll().stream()
                    .map(Rights::getCompanyCode)
                    .collect(java.util.stream.Collectors.toSet());
            List<Transaction> convertedR = transactionRepository.findAll().stream()
                    .filter(t -> t.getCompanyCode() != null && t.getCompanyCode().contains(".R"))
                    .filter(t -> Boolean.TRUE.equals(t.getDisabled()) && !Boolean.TRUE.equals(t.getConverted()))
                    .filter(t -> nWithRights.contains(t.getCompanyCode().replace(".R", ".N")))
                    .toList();
            if (!convertedR.isEmpty()) {
                convertedR.forEach(t -> t.setConverted(true));
                transactionRepository.saveAll(convertedR);
                log.info("Marked {} disabled .R transactions as converted (one-time)", convertedR.size());
            }
            markMigrationDone(RIGHTS_CONVERTED_FLAG);
        }
    }

    private static final String MIGRATIONS_COLLECTION = "app_migrations";

    private boolean migrationDone(String key) {
        return mongoTemplate.getCollection(MIGRATIONS_COLLECTION)
                .countDocuments(new org.bson.Document("_id", key)) > 0;
    }

    private void markMigrationDone(String key) {
        mongoTemplate.getCollection(MIGRATIONS_COLLECTION)
                .insertOne(new org.bson.Document("_id", key)
                        .append("ranAt", LocalDateTime.now().toString()));
    }
}
