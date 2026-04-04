package com.personal.stocktracker.service;

import com.personal.stocktracker.document.Transaction;
import com.personal.stocktracker.document.TransactionType;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class PdfParserService {

    private static final DateTimeFormatter PDF_DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Pattern TRANSACTION_HEADER_PATTERN =
            Pattern.compile("^(Purchase of|Sale of)\\s+(.+?)\\s*\\(\\s*(\\w+\\.\\w)");

    // Total line pattern: e.g. "150.505 0.1330.1000 0.02 0.45 152.19Total 0.96 0.11 0.000.02"
    // We use the Total line because it's cleaner (no dates mixed in)
    // Format: GrossAmount + Count + Exchange + Price + CDS + GOV_CESS + NetAmount + "Total" + Brokerage + SEC + ForeignBrokerage + ClearingFees
    private static final Pattern TOTAL_LINE_PATTERN =
            Pattern.compile("^([\\d.]+)(\\d+)\\s+([\\d.]+)([\\d.]+)\\s+([\\d.]+)\\s+([\\d.]+)\\s+([\\d.]+)Total\\s+([\\d.]+)\\s+([\\d.]+)\\s+([\\d.]+)([\\d.]+)$");

    public record ParsedTransaction(Transaction transaction, String companyName) {}

    public List<ParsedTransaction> parseTradeConfirmation(MultipartFile file) {
        String text;
        try (PDDocument document = Loader.loadPDF(file.getBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            text = stripper.getText(document);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse PDF file: " + e.getMessage(), e);
        }

        log.debug("Extracted PDF text for parsing");

        List<ParsedTransaction> transactions = new ArrayList<>();
        String[] lines = text.split("\\r?\\n");

        String currentCompanyName = null;
        String currentCompanyCode = null;
        TransactionType currentType = null;

        // Date pattern to identify data lines
        Pattern dateInLinePattern = Pattern.compile("\\d{2}/\\d{2}/\\d{4}");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            // Check for transaction header
            Matcher headerMatcher = TRANSACTION_HEADER_PATTERN.matcher(line);
            if (headerMatcher.find()) {
                String direction = headerMatcher.group(1);
                currentCompanyName = headerMatcher.group(2).trim();
                currentCompanyCode = headerMatcher.group(3).toUpperCase();
                currentType = direction.equals("Purchase of") ? TransactionType.BUY : TransactionType.SELL;
                continue;
            }

            // Skip separator lines, Total lines, and empty lines
            if (line.startsWith("---") || line.startsWith("===") || line.isEmpty()) {
                continue;
            }

            // Skip Total lines (aggregated) — we parse individual data lines instead
            if (line.contains("Total")) {
                // Reset after total (end of this company's transaction block)
                currentCompanyCode = null;
                currentCompanyName = null;
                currentType = null;
                continue;
            }

            // Parse individual data lines — they contain dates (dd/MM/yyyy)
            if (currentCompanyCode != null && dateInLinePattern.matcher(line).find()) {
                try {
                    // Normalize data line: strip dates and contract numbers to make it parseable
                    // Data line example: "150.505 0.1330.10 0.02 0.45 152.19 25/03/20262026325231 0.96 0.1123/03/2026 0.000.02"
                    // Remove dates (dd/MM/yyyy)
                    String normalized = line.replace(",", "");

                    // Extract trade date from this line before removing dates
                    Matcher dateMatcher = dateInLinePattern.matcher(normalized);
                    LocalDate lineTradeDate = null;
                    while (dateMatcher.find()) {
                        lineTradeDate = LocalDate.parse(dateMatcher.group(), PDF_DATE_FORMAT);
                    }

                    // Remove all dates
                    normalized = normalized.replaceAll("\\d{2}/\\d{2}/\\d{4}", "");
                    // Remove contract numbers (long digit sequences like 2026325231)
                    normalized = normalized.replaceAll("\\b\\d{8,}\\b", "");
                    // Clean up multiple spaces
                    normalized = normalized.replaceAll("\\s+", " ").trim();

                    // Now normalized looks like: "150.505 0.1330.10 0.02 0.45 152.19 0.96 0.11 0.000.02"
                    // This has the same structure as the left+right of a Total line
                    // Split into: leftPart (gross+count exchange+price cds govcess netamount) + rightPart (brokerage sec foreign+clearing)

                    String[] fields = normalized.split("\\s+");
                    if (fields.length < 8) {
                        log.warn("Data line has fewer than 8 fields after normalization: {}", normalized);
                        continue;
                    }

                    // Fields: [0]=gross+count [1]=exchange+price [2]=cds [3]=govcess [4]=netamount [5]=brokerage [6]=sec [7]=foreign+clearing
                    String grossCountStr = fields[0];
                    String exchangePriceStr = fields[1];
                    BigDecimal cds = new BigDecimal(fields[2]);
                    BigDecimal govCess = new BigDecimal(fields[3]);
                    // fields[4] = netAmount (not needed for our purposes)
                    BigDecimal brokerage = new BigDecimal(fields[5]);
                    BigDecimal sec = new BigDecimal(fields[6]);
                    BigDecimal clearingAndForeign = BigDecimal.ZERO;
                    if (fields.length >= 8) {
                        List<BigDecimal> vals = splitConcatenatedDecimals(fields[7]);
                        for (BigDecimal v : vals) {
                            clearingAndForeign = clearingAndForeign.add(v);
                        }
                    }

                    // Parse gross+count
                    Pattern grossCountPattern = Pattern.compile("^(\\d+\\.\\d{2})(\\d+)$");
                    Matcher gcMatcher = grossCountPattern.matcher(grossCountStr);
                    if (!gcMatcher.matches()) {
                        log.warn("Cannot parse gross+count: {}", grossCountStr);
                        continue;
                    }
                    int count = Integer.parseInt(gcMatcher.group(2));

                    // Parse exchange+price: exchange has 2 decimal places, price has 2 or 4
                    BigDecimal exchange;
                    BigDecimal price;
                    Pattern ep4Pattern = Pattern.compile("^(\\d+\\.\\d{2})(\\d+\\.\\d{2,4})$");
                    Matcher epMatcher = ep4Pattern.matcher(exchangePriceStr);
                    if (epMatcher.matches()) {
                        exchange = new BigDecimal(epMatcher.group(1));
                        price = new BigDecimal(epMatcher.group(2));
                    } else {
                        log.warn("Cannot parse exchange+price: {}", exchangePriceStr);
                        continue;
                    }

                    BigDecimal commission = brokerage.add(sec).add(exchange).add(cds).add(govCess).add(clearingAndForeign);

                    Transaction transaction = Transaction.builder()
                            .companyCode(currentCompanyCode)
                            .type(currentType)
                            .count(count)
                            .price(price)
                            .commission(commission)
                            .date(lineTradeDate)
                            .createdAt(LocalDateTime.now())
                            .build();

                    transactions.add(new ParsedTransaction(transaction, currentCompanyName));
                    log.info("Parsed transaction: {} {} {} shares @ {} commission={}",
                            currentType, currentCompanyCode, count, price, commission);
                } catch (Exception e) {
                    log.warn("Failed to parse data line: {}. Error: {}", line, e.getMessage());
                }
            }
        }

        // Set trade date for any transactions that didn't get a date from their line
        LocalDate fallbackDate = extractTradeDate(text);
        for (ParsedTransaction pt : transactions) {
            if (pt.transaction().getDate() == null) {
                pt.transaction().setDate(fallbackDate);
            }
        }

        return transactions;
    }

    private Transaction parseTotalLine(String line, String companyCode, TransactionType type) {
        // Strip commas from numbers (e.g. "4,700.00" -> "4700.00")
        line = line.replace(",", "");

        // Split on "Total"
        String[] parts = line.split("Total");
        if (parts.length != 2) {
            log.warn("Cannot split total line on 'Total': {}", line);
            return null;
        }

        String leftPart = parts[0].trim();
        String rightPart = parts[1].trim();

        // Right part: "0.96 0.11 0.000.02" = Brokerage SEC ForeignBrokerage+ClearingFees
        String[] rightFields = rightPart.split("\\s+");
        BigDecimal brokerage = BigDecimal.ZERO;
        BigDecimal sec = BigDecimal.ZERO;
        BigDecimal clearingAndForeign = BigDecimal.ZERO;

        if (rightFields.length >= 2) {
            brokerage = new BigDecimal(rightFields[0]);
            sec = new BigDecimal(rightFields[1]);
        }
        if (rightFields.length >= 3) {
            // This field is concatenated: "0.000.02" = foreignBrokerage + clearingFees
            String concat = rightFields[2];
            // Split concatenated decimals: find pattern like "0.00" + "0.02"
            List<BigDecimal> vals = splitConcatenatedDecimals(concat);
            for (BigDecimal v : vals) {
                clearingAndForeign = clearingAndForeign.add(v);
            }
        }

        // Left part: "150.505 0.1330.1000 0.02 0.45 152.19"
        // Fields: GrossAmount+Count Exchange+Price CDS GOV_CESS NetAmount
        String[] leftFields = leftPart.split("\\s+");

        // Parse from right side of left part (known fields)
        // Last field: NetAmount
        // Second to last: GOV_CESS
        // Third to last: CDS
        // Then we have the tricky concatenated fields

        if (leftFields.length < 4) {
            log.warn("Left part has fewer than 4 fields: {}", leftPart);
            return null;
        }

        BigDecimal netAmount = new BigDecimal(leftFields[leftFields.length - 1]);
        BigDecimal govCess = new BigDecimal(leftFields[leftFields.length - 2]);
        BigDecimal cds = new BigDecimal(leftFields[leftFields.length - 3]);

        // Remaining concatenated fields contain: GrossAmount+Count Exchange+Price
        // e.g. "150.505" "0.1330.1000"
        // GrossAmount+Count: "150.505" -> gross=150.50, count=5
        // Exchange+Price: "0.1330.1000" -> exchange=0.13, price=30.1000

        // First field: GrossAmount concatenated with Count
        String grossCountStr = leftFields[0];
        // Second field: Exchange concatenated with Price
        String exchangePriceStr = leftFields[1];

        // For grossCount: the count is an integer at the end
        // e.g. "150.505" -> "150.50" + "5", "116.004" -> "116.00" + "4", "73.809" -> "73.80" + "9"
        // Find where the decimal amount ends and the integer count begins
        // The gross amount has exactly 2 decimal places, so pattern is: digits.digits + integer
        Pattern grossCountPattern = Pattern.compile("^(\\d+\\.\\d{2})(\\d+)$");
        Matcher gcMatcher = grossCountPattern.matcher(grossCountStr);
        if (!gcMatcher.matches()) {
            log.warn("Cannot parse gross+count: {}", grossCountStr);
            return null;
        }
        BigDecimal grossAmount = new BigDecimal(gcMatcher.group(1));
        int count = Integer.parseInt(gcMatcher.group(2));

        // For exchangePrice: "0.1330.1000" -> exchange has 2 decimal places, price has 4
        // Pattern: exchange(digits.dd) + price(digits.dddd)
        Pattern exchangePricePattern = Pattern.compile("^(\\d+\\.\\d{2})(\\d+\\.\\d{4})$");
        Matcher epMatcher = exchangePricePattern.matcher(exchangePriceStr);
        if (!epMatcher.matches()) {
            log.warn("Cannot parse exchange+price: {}", exchangePriceStr);
            return null;
        }
        BigDecimal exchange = new BigDecimal(epMatcher.group(1));
        BigDecimal price = new BigDecimal(epMatcher.group(2));

        BigDecimal commission = brokerage.add(sec).add(exchange).add(cds).add(govCess).add(clearingAndForeign);

        return Transaction.builder()
                .companyCode(companyCode)
                .type(type)
                .count(count)
                .price(price)
                .commission(commission)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private List<BigDecimal> splitConcatenatedDecimals(String str) {
        List<BigDecimal> result = new ArrayList<>();
        // Match pattern like "0.000.02" -> "0.00" + "0.02"
        Pattern p = Pattern.compile("\\d+\\.\\d+");
        Matcher m = p.matcher(str);
        while (m.find()) {
            result.add(new BigDecimal(m.group()));
        }
        // If only one match but string looks like two concatenated (e.g., "0.000.02")
        if (result.size() == 1 && str.indexOf('.') != str.lastIndexOf('.')) {
            result.clear();
            // Split at second decimal point
            int firstDot = str.indexOf('.');
            int secondDot = str.indexOf('.', firstDot + 1);
            // Find where second number starts: look backwards from second dot
            int splitPos = secondDot;
            while (splitPos > 0 && Character.isDigit(str.charAt(splitPos - 1)) && splitPos - 1 > firstDot) {
                splitPos--;
            }
            // If splitPos is right after first decimal digits
            String first = str.substring(0, splitPos);
            String second = str.substring(splitPos);
            try {
                result.add(new BigDecimal(first));
                result.add(new BigDecimal(second));
            } catch (NumberFormatException e) {
                result.clear();
                result.add(new BigDecimal(str));
            }
        }
        return result;
    }

    private LocalDate extractTradeDate(String text) {
        // Look for "Trade Date" line or date in the header area
        // The PDF has: "23/03/2026" as trade date
        Pattern datePattern = Pattern.compile("on\\s+(\\d{2}/\\d{2}/\\d{4})");
        Matcher m = datePattern.matcher(text);
        if (m.find()) {
            return LocalDate.parse(m.group(1), PDF_DATE_FORMAT);
        }
        // Fallback: look for standalone date
        Pattern standaloneDate = Pattern.compile("^(\\d{2}/\\d{2}/\\d{4})$", Pattern.MULTILINE);
        Matcher sm = standaloneDate.matcher(text);
        if (sm.find()) {
            return LocalDate.parse(sm.group(1), PDF_DATE_FORMAT);
        }
        return LocalDate.now();
    }
}
