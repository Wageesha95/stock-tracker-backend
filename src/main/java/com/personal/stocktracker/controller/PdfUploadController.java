package com.personal.stocktracker.controller;

import com.personal.stocktracker.document.PdfUploadRecord;
import com.personal.stocktracker.document.Transaction;
import com.personal.stocktracker.dto.CompanyRequest;
import com.personal.stocktracker.dto.TransactionRequest;
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

    private static final Pattern FILENAME_DATE_PATTERN = Pattern.compile("(\\d{2})_(\\d{2})_(\\d{4})");

    private String currentUsername() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    private LocalDate extractDateFromFilename(String filename) {
        if (filename == null) return null;
        Matcher m = FILENAME_DATE_PATTERN.matcher(filename);
        if (m.find()) {
            return LocalDate.parse(m.group(1) + "/" + m.group(2) + "/" + m.group(3),
                    DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        }
        return null;
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> previewTradeConfirmation(@RequestParam("file") MultipartFile file) {
        String filename = file.getOriginalFilename();
        LocalDate tradeDate = extractDateFromFilename(filename);

        String username = currentUsername();
        if (tradeDate != null && pdfUploadRecordRepository.existsByUserIdAndTradeDate(username, tradeDate)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "A trade confirmation for " + tradeDate + " has already been uploaded"));
        }

        List<ParsedTransaction> parsed = pdfParserService.parseTradeConfirmation(file);

        List<Map<String, Object>> preview = new ArrayList<>();
        for (ParsedTransaction pt : parsed) {
            Transaction tx = pt.transaction();
            preview.add(Map.of(
                    "companyCode", tx.getCompanyCode(),
                    "companyName", pt.companyName(),
                    "date", tx.getDate().toString(),
                    "type", tx.getType().name(),
                    "count", tx.getCount(),
                    "price", tx.getPrice(),
                    "commission", tx.getCommission()
            ));
        }

        return ResponseEntity.ok(preview);
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadTradeConfirmation(@RequestParam("file") MultipartFile file) {
        String filename = file.getOriginalFilename();
        LocalDate tradeDate = extractDateFromFilename(filename);
        String username = currentUsername();

        if (tradeDate != null && pdfUploadRecordRepository.existsByUserIdAndTradeDate(username, tradeDate)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "A trade confirmation for " + tradeDate + " has already been uploaded"));
        }

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
            txRequest.setDate(pt.transaction().getDate());
            txRequest.setType(pt.transaction().getType());
            txRequest.setCount(pt.transaction().getCount());
            txRequest.setPrice(pt.transaction().getPrice());
            txRequest.setCommission(pt.transaction().getCommission());

            Transaction saved = transactionService.createTransaction(txRequest, username);
            savedTransactions.add(saved);
            transactionIds.add(saved.getId());
        }

        // Save upload record
        PdfUploadRecord record = PdfUploadRecord.builder()
                .userId(username)
                .filename(filename)
                .tradeDate(tradeDate)
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
