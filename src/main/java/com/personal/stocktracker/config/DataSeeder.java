package com.personal.stocktracker.config;

import com.personal.stocktracker.document.Broker;
import com.personal.stocktracker.document.Dividend;
import com.personal.stocktracker.document.PdfUploadRecord;
import com.personal.stocktracker.document.Transaction;
import com.personal.stocktracker.document.User;
import com.personal.stocktracker.repository.BrokerRepository;
import com.personal.stocktracker.repository.DividendRepository;
import com.personal.stocktracker.repository.PdfUploadRecordRepository;
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
    private final MongoTemplate mongoTemplate;

    @Override
    public void run(String... args) {
        seedUsers();
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

    private void seedBrokers() {
        for (String name : List.of("Softlogic", "Almas")) {
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
    }
}
