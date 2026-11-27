package br.com.cshub.taxify.service.reform;

import br.com.cshub.taxify.common.CsvCells;
import br.com.cshub.taxify.common.DocumentNumbers;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.dto.reform.StockSurveyRowDTO;
import br.com.cshub.taxify.enumerated.TaxRegime;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The CSV of the stock credit survey (section 7): {@code ;}, UTF-8 with BOM, {@code \r\n}, decimal comma without
 * thousands separator, dates {@code dd/MM/yyyy}, every text cell through {@link CsvCells#safe}; the last line is the
 * fixed notice (CE-RN-13).
 */
@Component
public class StockSurveyCsv {

    private static final char SEPARATOR = ';';
    private static final String LINE = "\r\n";
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final List<String> HEADER_KEYS = List.of("company.name", "cnpj", "regime.reference", "regime.next.year",
            "classification", "reason", "purchases", "purchase.items", "inbound.notes", "months.with.inbound",
            "months.evaluated", "coverage", "first.inbound", "last.inbound", "status", "status.changed.by",
            "status.changed.at", "calculated.at");

    private final MessageService messageService;

    public StockSurveyCsv(MessageService messageService) {
        this.messageService = messageService;
    }

    public byte[] write(List<StockSurveyRowDTO> rows, String notice) {
        StringBuilder csv = new StringBuilder();
        line(csv, HEADER_KEYS.stream().map(key -> messageService.getMessage("reform.stock.csv." + key)).toList());
        for (StockSurveyRowDTO row : rows) {
            line(csv, cells(row));
        }
        line(csv, List.of(messageService.getMessage("reform.stock.csv.notice"), notice));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(BOM);
        out.writeBytes(csv.toString().getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private List<String> cells(StockSurveyRowDTO row) {
        List<String> cells = new ArrayList<>();
        cells.add(row.companyName());
        cells.add(DocumentNumbers.format(row.cnpj()));
        cells.add(regime(row.referenceRegime()));
        cells.add(regime(row.nextYearRegime()));
        cells.add(label("classification", row.classification()));
        cells.add(row.classificationReason());
        cells.add(money(row.yearPurchases()));
        cells.add(String.valueOf(row.purchaseItems()));
        cells.add(String.valueOf(row.inboundInvoices()));
        cells.add(String.valueOf(row.monthsWithInbound()));
        cells.add(String.valueOf(row.monthsEvaluated()));
        cells.add(label("coverage", row.coverage()));
        cells.add(date(row.firstInbound()));
        cells.add(date(row.lastInbound()));
        cells.add(label("status", row.status()));
        cells.add(row.statusChangedBy());
        cells.add(dateTime(row.statusChangedAt()));
        cells.add(dateTime(row.calculatedAt()));
        return cells;
    }

    private static void line(StringBuilder csv, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                csv.append(SEPARATOR);
            }
            csv.append(CsvCells.cell(cells.get(i), SEPARATOR));
        }
        csv.append(LINE);
    }

    private String label(String group, String value) {
        return value == null ? "" : messageService.getMessage("reform.stock." + group + "." + value);
    }

    private static String regime(String value) {
        if (value == null) {
            return "";
        }
        try {
            return TaxRegime.valueOf(value).label();
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    static String money(BigDecimal value) {
        return value == null ? "" : value.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    private static String date(LocalDate value) {
        return value == null ? "" : DATE.format(value);
    }

    private static String dateTime(OffsetDateTime value) {
        return value == null ? "" : DATE_TIME.format(value);
    }
}
