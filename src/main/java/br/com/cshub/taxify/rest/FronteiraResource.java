package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.security.ReadOnlyOperation;
import br.com.cshub.taxify.constants.AppConstants;
import br.com.cshub.taxify.dto.fronteira.FronteiraDTOs;
import br.com.cshub.taxify.dto.fronteira.FronteiraRuleDTOs;
import br.com.cshub.taxify.enumerated.Permission;
import br.com.cshub.taxify.security.RequiresPermission;
import br.com.cshub.taxify.service.fronteira.EfiscoMemoryService;
import br.com.cshub.taxify.service.fronteira.EfiscoSyncService;
import br.com.cshub.taxify.service.fronteira.FronteiraRuleService;
import br.com.cshub.taxify.service.fronteira.FronteiraService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fronteira PE: the e-Fisco extract of the ICMS antecipado, imported by hand, next to Taxify's own
 * calculation - and the table that calculation uses (rates and the accountant's formulas). A company's
 * rule is written with the audit permission; a rule for every company, and the review of any rule,
 * with the fiscal rules permission.
 */
@RequiresPermission(Permission.INVOICES_VIEW)
@RestController
@RequestMapping(AppConstants.API + AppConstants.V1 + "/fronteira")
public class FronteiraResource {

    private final FronteiraService service;
    private final FronteiraRuleService ruleService;
    private final EfiscoSyncService efiscoSyncService;
    private final EfiscoMemoryService efiscoMemoryService;

    public FronteiraResource(FronteiraService service, FronteiraRuleService ruleService, EfiscoSyncService efiscoSyncService,
                             EfiscoMemoryService efiscoMemoryService) {
        this.service = service;
        this.ruleService = ruleService;
        this.efiscoSyncService = efiscoSyncService;
        this.efiscoMemoryService = efiscoMemoryService;
    }

    /** e-Fisco's memória of a note already fetched (by the extract's recordNumber); 204 when never fetched. */
    @GetMapping("/memoria")
    public ResponseEntity<EfiscoMemoryService.MemoryDTO> efiscoMemory(@RequestParam String companyId, @RequestParam("registro") String recordNumber) {
        return efiscoMemoryService.kept(service.companyOfPernambuco(companyId), recordNumber)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** Fetches the note's memória from e-Fisco now, with the accountant's certificate, and keeps it. */
    @RequiresPermission(Permission.INVOICES_AUDIT)
    @ReadOnlyOperation
    @PostMapping("/memoria/buscar")
    public ResponseEntity<EfiscoMemoryService.MemoryDTO> fetchEfiscoMemory(@RequestParam String companyId, @RequestParam("registro") String recordNumber) {
        return ResponseEntity.ok(efiscoMemoryService.fetch(service.companyOfPernambuco(companyId), recordNumber));
    }

    /** What the "Sincronizar com e-Fisco" button gets back: how the run went and the comparison after it. */
    public record EfiscoSync(EfiscoSyncService.SyncResult sync, FronteiraDTOs.Report report) {
    }

    /**
     * Fetches the period's extract of the company from e-Fisco now, with the firm's accountant's certificate, and
     * imports it - the same the scheduled run does, for the company and month on screen.
     */
    @RequiresPermission(Permission.INVOICES_AUDIT)
    @PostMapping("/sincronizar")
    public ResponseEntity<EfiscoSync> syncWithEfisco(@RequestParam String companyId, @RequestParam(name = "periodo", required = false) String requestedPeriod) {
        var company = service.companyOfPernambuco(companyId);
        var period = service.periodOrCurrent(requestedPeriod);
        EfiscoSyncService.SyncResult result = efiscoSyncService.syncCompany(company, period);
        return ResponseEntity.ok(new EfiscoSync(result, service.report(companyId, period.toString())));
    }

    /**
     * The comparison of a period ("2026-11"); blank = the latest imported. {@code cfops} (optional, {@code cfops=6102,6403}
     * or repeated): the CFOP cut in {@code cfopFilter}, the rest of the report unchanged.
     */
    @GetMapping
    public ResponseEntity<FronteiraDTOs.Report> report(@RequestParam String companyId, @RequestParam(name = "periodo", required = false) String period,
                                                       @RequestParam(name = "cfops", required = false) List<String> cfops) {
        return ResponseEntity.ok(service.report(companyId, period, cfops));
    }

    /** The month's report (PDF): summary with the guide after the adjustments, the notes to revise and the rules used. */
    @GetMapping(value = "/relatorio.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> reportPdf(@RequestParam String companyId, @RequestParam(name = "periodo", required = false) String period) {
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"fronteira-" + (period == null ? "" : period) + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(service.reportPdf(companyId, period));
    }

    /** The month's notes as a spreadsheet, with the calculation and the rules of each one. */
    @GetMapping("/relatorio.csv")
    public ResponseEntity<byte[]> reportCsv(@RequestParam String companyId, @RequestParam(name = "periodo", required = false) String period) {
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"fronteira-" + (period == null ? "" : period) + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(service.reportCsv(companyId, period));
    }

    /**
     * The draft of the revision request to SEFAZ-PE (PDF) with the notes charged beyond Taxify's calculation;
     * 409 when there is none.
     */
    @GetMapping("/pedido-revisao.pdf")
    public ResponseEntity<byte[]> revisionRequestPdf(@RequestParam String companyId, @RequestParam(name = "periodo", required = false) String period) {
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"pedido-revisao-fronteira-" + (period == null ? "" : period) + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(service.revisionRequestPdf(companyId, period));
    }

    /** The same draft as plain text, to paste in e-Fisco's form. */
    @GetMapping("/pedido-revisao.txt")
    public ResponseEntity<byte[]> revisionRequestText(@RequestParam String companyId, @RequestParam(name = "periodo", required = false) String period) {
        return ResponseEntity.ok()
                .header("Content-Disposition", "inline; filename=\"pedido-revisao-fronteira-" + (period == null ? "" : period) + ".txt\"")
                .contentType(MediaType.parseMediaType("text/plain; charset=UTF-8"))
                .body(service.revisionRequestText(companyId, period));
    }

    /** The kept PDF of the period's extract, to open in the browser ("Ver PDF"). */
    @GetMapping(value = "/extrato/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> extractPdf(@RequestParam String companyId, @RequestParam("periodo") String period) {
        return ResponseEntity.ok()
                .header("Content-Disposition", "inline; filename=\"extrato-" + period + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(service.extractPdf(companyId, period));
    }

    /** The extract PDF as downloaded from e-Fisco; the period comes from the file and replaces an earlier import. */
    @RequiresPermission(Permission.INVOICES_AUDIT)
    @PostMapping(value = "/importar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FronteiraDTOs.Report> importExtract(@RequestParam String companyId,
                                                              @RequestParam("file") MultipartFile file) throws IOException {
        return ResponseEntity.ok(service.importExtract(companyId, file.getOriginalFilename(), file.getBytes()));
    }

    // ---------------------------------------------------------------- table and formulas

    /** The global rules plus the company's own; without company, only the global ones. */
    @GetMapping("/regras")
    public ResponseEntity<List<FronteiraRuleDTOs.Rule>> rules(@RequestParam(required = false) String companyId) {
        return ResponseEntity.ok(ruleService.list(companyId));
    }

    /** The variables a formula can use, with what each one is. */
    @GetMapping("/regras/variaveis")
    public ResponseEntity<Map<String, String>> variables() {
        return ResponseEntity.ok(br.com.cshub.taxify.service.fronteira.FormulaEvaluator.VARIABLES);
    }

    /** Checks a formula and works it out - with a note item's values ({@code variaveis}) or with sample ones. */
    @ReadOnlyOperation
    @PostMapping("/regras/testar")
    public ResponseEntity<FronteiraRuleDTOs.FormulaCheck> check(@RequestBody FormulaTest body) {
        return ResponseEntity.ok(ruleService.check(body.formula(), body.variaveis()));
    }

    public record FormulaTest(String formula, Map<String, java.math.BigDecimal> variaveis) {
    }

    @RequiresPermission(Permission.INVOICES_AUDIT)
    @PostMapping("/regras")
    public ResponseEntity<FronteiraRuleDTOs.Rule> create(@RequestBody FronteiraRuleDTOs.RuleInput input) {
        return ResponseEntity.ok(ruleService.create(input, false));
    }

    @RequiresPermission(Permission.INVOICES_AUDIT)
    @PutMapping("/regras/{id}")
    public ResponseEntity<FronteiraRuleDTOs.Rule> update(@PathVariable UUID id, @RequestBody FronteiraRuleDTOs.RuleInput input) {
        return ResponseEntity.ok(ruleService.update(id, input, false));
    }

    @RequiresPermission(Permission.INVOICES_AUDIT)
    @DeleteMapping("/regras/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        ruleService.delete(id, false);
        return ResponseEntity.noContent().build();
    }

    @RequiresPermission(Permission.FISCAL_RULES_MANAGE)
    @PostMapping("/regras/geral")
    public ResponseEntity<FronteiraRuleDTOs.Rule> createGlobal(@RequestBody FronteiraRuleDTOs.RuleInput input) {
        return ResponseEntity.ok(ruleService.create(input, true));
    }

    @RequiresPermission(Permission.FISCAL_RULES_MANAGE)
    @PutMapping("/regras/geral/{id}")
    public ResponseEntity<FronteiraRuleDTOs.Rule> updateGlobal(@PathVariable UUID id, @RequestBody FronteiraRuleDTOs.RuleInput input) {
        return ResponseEntity.ok(ruleService.update(id, input, true));
    }

    @RequiresPermission(Permission.FISCAL_RULES_MANAGE)
    @DeleteMapping("/regras/geral/{id}")
    public ResponseEntity<Void> deleteGlobal(@PathVariable UUID id) {
        ruleService.delete(id, true);
        return ResponseEntity.noContent().build();
    }

    /** The fiscal team checked the rule (conferida=false takes it back); results that use it stop being "a conferir". */
    @RequiresPermission(Permission.FISCAL_RULES_MANAGE)
    @PostMapping("/regras/{id}/conferencia")
    public ResponseEntity<FronteiraRuleDTOs.Rule> review(@PathVariable UUID id, @RequestParam(defaultValue = "true") boolean conferida) {
        return ResponseEntity.ok(ruleService.review(id, conferida));
    }

    @RequiresPermission(Permission.FISCAL_RULES_MANAGE)
    @PostMapping("/regras/{id}/ativa")
    public ResponseEntity<FronteiraRuleDTOs.Rule> enable(@PathVariable UUID id, @RequestParam boolean ativa) {
        return ResponseEntity.ok(ruleService.enable(id, ativa));
    }
}
