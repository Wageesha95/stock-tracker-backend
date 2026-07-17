package com.personal.stocktracker.controller;

import com.personal.stocktracker.document.PdfUploadRecord;
import com.personal.stocktracker.document.Transaction;
import com.personal.stocktracker.dto.CompanyRequest;
import com.personal.stocktracker.dto.TransactionRequest;
import com.personal.stocktracker.repository.BrokerRepository;
import com.personal.stocktracker.repository.CompanyRepository;
import com.personal.stocktracker.repository.PdfUploadRecordRepository;
import com.personal.stocktracker.repository.TransactionRepository;
import com.personal.stocktracker.service.CompanyService;
import com.personal.stocktracker.service.PdfParserService;
import com.personal.stocktracker.service.PdfParserService.ParsedTransaction;
import com.personal.stocktracker.service.TransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@RestController
@RequestMapping("/api/pdf")
@RequiredArgsConstructor
public class PdfUploadController {

    private final PdfParserService pdfParserService;
    private final TransactionService transactionService;
    private final CompanyService companyService;
    private final CompanyRepository companyRepository;
    private final PdfUploadRecordRepository pdfUploadRecordRepository;
    private final TransactionRepository transactionRepository;
    private final BrokerRepository brokerRepository;

    // DD_MM_YYYY format
    private static final Pattern FILENAME_DATE_NUMERIC = Pattern.compile("(\\d{2})_(\\d{2})_(\\d{4})");
    // DD_Month_YYYY format (e.g. 26_March_2026)
    private static final Pattern FILENAME_DATE_NAMED = Pattern.compile("(\\d{1,2})_(January|February|March|April|May|June|July|August|September|October|November|December)_(\\d{4})", Pattern.CASE_INSENSITIVE);

    private String currentUsername() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    private LocalDate extractDateFromFilename(String filename) {
        if (filename == null) return null;
        log.info("Extracting date from filename: {}", filename);

        // Try named month format first (26_March_2026)
        Matcher nm = FILENAME_DATE_NAMED.matcher(filename);
        if (nm.find()) {
            String dateStr = nm.group(1) + " " + nm.group(2) + " " + nm.group(3);
            log.info("Matched named month format: {}", dateStr);
            return LocalDate.parse(dateStr,
                    DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale.ENGLISH));
        }

        // Try numeric format (26_03_2026)
        Matcher m = FILENAME_DATE_NUMERIC.matcher(filename);
        if (m.find()) {
            log.info("Matched numeric format: {}_{}_{}", m.group(1), m.group(2), m.group(3));
            return LocalDate.parse(m.group(1) + "/" + m.group(2) + "/" + m.group(3),
                    DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        }

        log.warn("No date pattern matched in filename: {}", filename);
        return null;
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> previewTradeConfirmation(@RequestParam("file") MultipartFile file) {
        String filename = file.getOriginalFilename();
        LocalDate suggestedDate = extractDateFromFilename(filename);

        List<ParsedTransaction> parsed = pdfParserService.parseTradeConfirmation(file);

        // If no date from filename, try to get from parsed transactions
        if (suggestedDate == null && !parsed.isEmpty()) {
            suggestedDate = parsed.get(0).transaction().getDate();
        }

        List<Map<String, Object>> transactions = new ArrayList<>();
        for (ParsedTransaction pt : parsed) {
            Transaction tx = pt.transaction();
            transactions.add(Map.of(
                    "companyCode", tx.getCompanyCode(),
                    "companyName", pt.companyName(),
                    "date", tx.getDate().toString(),
                    "type", tx.getType().name(),
                    "count", tx.getCount(),
                    "price", tx.getPrice(),
                    "commission", tx.getCommission()
            ));
        }

        Map<String, Object> response = new HashMap<>();
        response.put("transactions", transactions);
        response.put("suggestedDate", suggestedDate != null ? suggestedDate.toString() : null);

        return ResponseEntity.ok(response);
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadTradeConfirmation(
            @RequestParam("file") MultipartFile file,
            @RequestParam("tradeDate") String tradeDateStr,
            @RequestParam("brokerId") String brokerId) {

        LocalDate tradeDate = LocalDate.parse(tradeDateStr);
        String username = currentUsername();

        // Validate broker exists
        if (!brokerRepository.existsById(brokerId)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid broker"));
        }

        // Check uniqueness: tradeDate + broker combination per user
        if (pdfUploadRecordRepository.existsByUserIdAndTradeDateAndBrokerId(username, tradeDate, brokerId)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "A trade confirmation for this date and broker has already been uploaded"));
        }

        String filename = file.getOriginalFilename();
        List<ParsedTransaction> parsed = pdfParserService.parseTradeConfirmation(file);

        List<Transaction> savedTransactions = new ArrayList<>();
        List<String> transactionIds = new ArrayList<>();

        for (ParsedTransaction pt : parsed) {
            String code = pt.transaction().getCompanyCode();
            if (!companyRepository.existsByCode(code)) {
                CompanyRequest companyRequest = new CompanyRequest();
                companyRequest.setCode(code);
                companyRequest.setName(pt.companyName());
                companyService.createCompany(companyRequest);
                log.info("Auto-registered company: {}", code);
            }

            TransactionRequest txRequest = new TransactionRequest();
            txRequest.setCompanyCode(code);
            txRequest.setDate(tradeDate);
            txRequest.setType(pt.transaction().getType());
            txRequest.setCount(pt.transaction().getCount());
            txRequest.setPrice(pt.transaction().getPrice());
            txRequest.setCommission(pt.transaction().getCommission());

            Transaction saved = transactionService.createTransaction(txRequest, username);
            // Tag the transaction with its broker so broker-based data filters can use it.
            saved.setBrokerId(brokerId);
            saved = transactionRepository.save(saved);
            savedTransactions.add(saved);
            transactionIds.add(saved.getId());
        }

        // Save upload record
        PdfUploadRecord record = PdfUploadRecord.builder()
                .userId(username)
                .filename(filename)
                .tradeDate(tradeDate)
                .brokerId(brokerId)
                .transactionIds(transactionIds)
                .transactionCount(transactionIds.size())
                .uploadedAt(LocalDateTime.now())
                .build();
        pdfUploadRecordRepository.save(record);

        return ResponseEntity.status(HttpStatus.CREATED).body(savedTransactions);
    }

    @GetMapping("/uploads")
    public ResponseEntity<List<PdfUploadRecord>> getUploads() {
        return ResponseEntity.ok(pdfUploadRecordRepository.findByUserIdOrderByUploadedAtDesc(currentUsername()));
    }

    @PutMapping("/uploads/{id}")
    public ResponseEntity<?> updateUpload(@PathVariable String id, @RequestBody Map<String, String> body) {
        String username = currentUsername();
        PdfUploadRecord record = pdfUploadRecordRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Upload record not found: " + id));
        if (!username.equals(record.getUserId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied"));
        }

        String tradeDateStr = body.get("tradeDate");
        String brokerId = body.get("brokerId");
        if (tradeDateStr == null || tradeDateStr.isBlank() || brokerId == null || brokerId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "tradeDate and brokerId are required"));
        }

        LocalDate tradeDate = LocalDate.parse(tradeDateStr);
        if (!brokerRepository.existsById(brokerId)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid broker"));
        }

        // Uniqueness: no other upload for the same date + broker (matches the unique index).
        var clash = pdfUploadRecordRepository.findByUserIdAndTradeDateAndBrokerId(username, tradeDate, brokerId);
        if (clash.isPresent() && !clash.get().getId().equals(id)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "A trade confirmation for this date and broker already exists"));
        }

        // Cascade the new date + broker onto every linked transaction.
        if (record.getTransactionIds() != null && !record.getTransactionIds().isEmpty()) {
            Iterable<Transaction> txns = transactionRepository.findAllById(record.getTransactionIds());
            for (Transaction tx : txns) {
                tx.setDate(tradeDate);
                tx.setBrokerId(brokerId);
            }
            transactionRepository.saveAll(txns);
        }

        record.setTradeDate(tradeDate);
        record.setBrokerId(brokerId);
        pdfUploadRecordRepository.save(record);

        return ResponseEntity.ok(record);
    }

    @DeleteMapping("/uploads/{id}")
    public ResponseEntity<Void> deleteUpload(@PathVariable String id) {
        PdfUploadRecord record = pdfUploadRecordRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Upload record not found: " + id));

        // Delete all linked transactions
        transactionRepository.deleteAllById(record.getTransactionIds());

        // Delete the upload record
        pdfUploadRecordRepository.deleteById(id);

        return ResponseEntity.noContent().build();
    }
}
