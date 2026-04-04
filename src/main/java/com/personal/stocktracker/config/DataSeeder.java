package com.personal.stocktracker.config;

import com.personal.stocktracker.document.Dividend;
import com.personal.stocktracker.document.PdfUploadRecord;
import com.personal.stocktracker.document.Transaction;
import com.personal.stocktracker.document.User;
import com.personal.stocktracker.repository.DividendRepository;
import com.personal.stocktracker.repository.PdfUploadRecordRepository;
import com.personal.stocktracker.repository.TransactionRepository;
import com.personal.stocktracker.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TransactionRepository transactionRepository;
    private final DividendRepository dividendRepository;
    private final PdfUploadRecordRepository pdfUploadRecordRepository;

    @Override
    public void run(String... args) {
        seedUsers();
        migrateExistingData();
    }

    private void seedUsers() {
        if (userRepository.findByUsername("imwageesha").isEmpty()) {
            userRepository.save(User.builder()
                    .username("imwageesha")
                    .password(passwordEncoder.encode("123123"))
                    .role("USER")
                    .createdAt(LocalDateTime.now())
                    .build());
            log.info("Created user: imwageesha");
        }

        if (userRepository.findByUsername("navod").isEmpty()) {
            userRepository.save(User.builder()
                    .username("navod")
                    .password(passwordEncoder.encode("dimsum"))
                    .role("USER")
                    .createdAt(LocalDateTime.now())
                    .build());
            log.info("Created user: navod");
        }

        if (userRepository.findByUsername("admin").isEmpty()) {
            userRepository.save(User.builder()
                    .username("admin")
                    .password(passwordEncoder.encode("admin"))
                    .role("ADMIN")
                    .createdAt(LocalDateTime.now())
                    .build());
            log.info("Created user: admin");
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
    }
}
