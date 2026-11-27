package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.constants.AppConstants;
import br.com.cshub.taxify.dto.reform.StockSurveyDetailDTO;
import br.com.cshub.taxify.dto.reform.StockSurveyEventDTO;
import br.com.cshub.taxify.dto.reform.StockSurveyListDTO;
import br.com.cshub.taxify.dto.reform.StockSurveyRowDTO;
import br.com.cshub.taxify.dto.reform.StockSurveyStatusChangeDTO;
import br.com.cshub.taxify.enumerated.Permission;
import br.com.cshub.taxify.security.RequiresPermission;
import br.com.cshub.taxify.service.reform.StockSurveyService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Stock credit survey (spec 2026-11-27, F5a): who may have the presumed CBS credit on the stock of 31/12/2026, by the
 * registered regime, and the weight of the 2026 purchases. Reads with {@code TAX_ASSESSMENT_VIEW}, writes with
 * {@code TAX_ASSESSMENT_CLOSE} (P-03); writes answer 403 in a blocked firm (FirmAccessInterceptor). Off by default:
 * every route answers 404 {@code error.reform.stock.disabled}. Not a credit calculation (CE-RN-13).
 */
@RequiresPermission(value = Permission.TAX_ASSESSMENT_VIEW, write = Permission.TAX_ASSESSMENT_CLOSE)
@RestController
@RequestMapping(AppConstants.API + AppConstants.V1 + "/reform/stock-credit")
public class StockCreditSurveyResource {

    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final StockSurveyService service;
    private final Clock clock;

    public StockCreditSurveyResource(StockSurveyService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    /**
     * @param classification HYPOTHESIS_I, HYPOTHESIS_II_III, NO_REGIME, OUT_OF_SCOPE (repeated or comma-separated)
     * @param status         NOT_EVALUATED, NOTIFIED, INVENTORY_RECEIVED, DISCARDED
     * @param coverage       NO_INBOUND, COMPLETE, PARTIAL
     * @param search         company name or CNPJ (digits or masked), at least 3 characters
     * @param sort           classification, yearPurchases, companyName or statusChangedAt, with ",asc" or ",desc"
     */
    @GetMapping
    public ResponseEntity<StockSurveyListDTO> list(@RequestParam(required = false) Integer year,
                                                   @RequestParam(required = false) List<String> classification,
                                                   @RequestParam(required = false) List<String> status,
                                                   @RequestParam(required = false) List<String> coverage,
                                                   @RequestParam(required = false) String search,
                                                   @RequestParam(required = false) Integer page,
                                                   @RequestParam(required = false) Integer size,
                                                   @RequestParam(required = false) String sort) {
        return ResponseEntity.ok(service.list(year, classification, status, coverage, search, page, size, sort));
    }

    /** The list's filters and order, every row (no paging). */
    @GetMapping("/export.csv")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) Integer year,
                                         @RequestParam(required = false) List<String> classification,
                                         @RequestParam(required = false) List<String> status,
                                         @RequestParam(required = false) List<String> coverage,
                                         @RequestParam(required = false) String search,
                                         @RequestParam(required = false) String sort) {
        byte[] body = service.csv(year, classification, status, coverage, search, sort);
        String fileName = "credito-estoque-" + year + "-" + FILE_DATE.format(LocalDate.now(clock)) + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(body);
    }

    @GetMapping("/{companyId}")
    public ResponseEntity<StockSurveyDetailDTO> detail(@PathVariable UUID companyId, @RequestParam(required = false) Integer year) {
        return ResponseEntity.ok(service.detail(companyId, year));
    }

    @GetMapping("/{companyId}/history")
    public ResponseEntity<List<StockSurveyEventDTO>> history(@PathVariable UUID companyId,
                                                             @RequestParam(required = false) Integer year) {
        return ResponseEntity.ok(service.history(companyId, year));
    }

    /** "Refresh now" (CE-RN-09): 409 with {@code retryAt} inside the cooldown. */
    @PostMapping("/{companyId}/refresh")
    public ResponseEntity<StockSurveyDetailDTO> refresh(@PathVariable UUID companyId, @RequestParam(required = false) Integer year) {
        return ResponseEntity.ok(service.refresh(companyId, year));
    }

    /** The contact status (CE-RN-10): 409 with {@code currentVersion} when someone else changed it first. */
    @PutMapping("/{companyId}/status")
    public ResponseEntity<StockSurveyRowDTO> changeStatus(@PathVariable UUID companyId, @RequestBody StockSurveyStatusChangeDTO body) {
        return ResponseEntity.ok(service.changeStatus(companyId, body));
    }
}
