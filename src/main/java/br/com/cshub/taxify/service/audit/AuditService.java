package br.com.cshub.taxify.service.audit;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.PlatformAdminService;
import br.com.cshub.taxify.domain.audit.AuditRule;
import br.com.cshub.taxify.domain.audit.AuditRun;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.pendency.Pendency;
import br.com.cshub.taxify.dto.audit.AuditDTOs;
import br.com.cshub.taxify.enumerated.PendencySeverity;
import br.com.cshub.taxify.enumerated.PendencyType;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.repository.AuditRuleRepository;
import br.com.cshub.taxify.repository.AuditRunRepository;
import br.com.cshub.taxify.repository.PendencyRepository;
import br.com.cshub.taxify.service.pendency.PendencyFinder;
import br.com.cshub.taxify.service.pendency.PendencyService;
import br.com.cshub.taxify.validation.CompanyValidation;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The Auditoria tipo malha (roadmap M9): runs {@link AuditEngine} for a company and competence, turns
 * the findings into pendencies (type AUDIT_FINDING, one per indication, closed by themselves when the
 * indication goes away), keeps the rules' catalog and writes the report (laudo) as PDF and CSV.
 * A rule the fiscal team hasn't checked yet only produces "a conferir" findings.
 */
@Service
public class AuditService {

    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final String SYSTEM = "sistema";

    private final AuditEngine engine;
    private final AuditRuleRepository ruleRepository;
    private final AuditRunRepository runRepository;
    private final PendencyRepository pendencyRepository;
    private final PendencyService pendencyService;
    private final CompanyValidation companyValidation;
    private final MessageService messageService;
    private final AuditorAware<String> auditorAware;
    private final PlatformAdminService platformAdminService;

    public AuditService(AuditEngine engine, AuditRuleRepository ruleRepository, AuditRunRepository runRepository,
                        PendencyRepository pendencyRepository, PendencyService pendencyService,
                        CompanyValidation companyValidation, MessageService messageService, AuditorAware<String> auditorAware,
                        PlatformAdminService platformAdminService) {
        this.platformAdminService = platformAdminService;
        this.engine = engine;
        this.ruleRepository = ruleRepository;
        this.runRepository = runRepository;
        this.pendencyRepository = pendencyRepository;
        this.pendencyService = pendencyService;
        this.companyValidation = companyValidation;
        this.messageService = messageService;
        this.auditorAware = auditorAware;
    }

    // ---------------------------------------------------------------- rules

    /** The catalog in the code's order - rows created on first use with the code's defaults. */
    @Transactional
    public List<AuditDTOs.Rule> rules() {
        return catalog().values().stream().map(AuditService::toDto).toList();
    }

    /** The fiscal team checked the rule (or takes the check back): from now on its findings are indications, not "a conferir". */
    @Transactional
    public AuditDTOs.Rule review(String code, boolean reviewed) {
        platformAdminService.assertCanManageGlobalRules(); // as regras da auditoria valem para todos os escritórios
        AuditRule rule = rule(code);
        rule.setReviewedBy(reviewed ? who() : null);
        rule.setReviewedAt(reviewed ? LocalDateTime.now() : null);
        return toDto(ruleRepository.save(rule));
    }

    @Transactional
    public AuditDTOs.Rule enable(String code, boolean enabled) {
        platformAdminService.assertCanManageGlobalRules();
        AuditRule rule = rule(code);
        rule.setEnabled(enabled);
        return toDto(ruleRepository.save(rule));
    }

    private AuditRule rule(String code) {
        AuditRule rule = catalog().get(code == null ? "" : code.strip().toUpperCase());
        if (rule == null) {
            throw new BadRequestException(messageService.getMessage("error.generic.bad.request"));
        }
        return rule;
    }

    /** Code -> row, creating the missing rows from {@link AuditRuleDefinition}. */
    Map<String, AuditRule> catalog() {
        Map<String, AuditRule> stored = ruleRepository.findAll().stream()
                .collect(Collectors.toMap(AuditRule::getCode, Function.identity(), (a, b) -> a));
        Map<String, AuditRule> catalog = new java.util.LinkedHashMap<>();
        for (AuditRuleDefinition definition : AuditRuleDefinition.values()) {
            AuditRule rule = stored.get(definition.name());
            if (rule == null) {
                rule = new AuditRule();
                rule.setCode(definition.name());
                rule.setTitle(definition.title);
                rule.setGroup(definition.group.name());
                rule.setSeverity(definition.severity);
                rule.setLegalBasis(definition.legalBasis);
                rule.setLink(definition.link);
                rule.setValidFrom(definition.validFrom);
                rule.setEnabled(true);
                rule.setDeleted(false);
                rule = ruleRepository.save(rule);
            }
            catalog.put(definition.name(), rule);
        }
        return catalog;
    }

    // ---------------------------------------------------------------- run and report

    /** Audits the company's competence now and returns the report. */
    @Transactional
    public AuditDTOs.Report run(String companyId, String competence) {
        Company company = company(companyId);
        YearMonth month = month(competence);
        audit(company, month, who());
        return report(company, month);
    }

    /** The audit itself - also what the daily job calls. @return findings open after the run */
    @Transactional
    public int audit(Company company, YearMonth month, String who) {
        Map<String, AuditRule> rules = catalog();
        AuditEngine.Result result = engine.audit(company, month);
        List<PendencyFinder.Finding> findings = result.findings().stream()
                .filter(finding -> {
                    AuditRule rule = rules.get(finding.rule().name());
                    return rule != null && rule.appliesTo(month);
                })
                .map(finding -> toPendency(finding, rules.get(finding.rule().name()), month))
                .toList();
        int open = pendencyService.syncScoped(company, Set.of(PendencyType.AUDIT_FINDING), month.toString(), findings);
        AuditRun run = runRepository.findByCompany_IdAndCompetence(company.getId(), month.toString()).orElseGet(() -> {
            AuditRun created = new AuditRun();
            created.setCompany(company);
            created.setCompetence(month.toString());
            created.setDeleted(false);
            return created;
        });
        run.setRanAt(LocalDateTime.now());
        run.setRanBy(who);
        run.setFindings(findings.size());
        run.setNotesChecked(result.notesChecked());
        runRepository.save(run);
        return open;
    }

    @Transactional
    public AuditDTOs.Report report(String companyId, String competence) {
        return report(company(companyId), month(competence));
    }

    private AuditDTOs.Report report(Company company, YearMonth month) {
        Map<String, AuditRule> rules = catalog();
        List<AuditDTOs.Finding> findings = pendencyRepository
                .findByCompany_IdAndTypeAndCompetence(company.getId(), PendencyType.AUDIT_FINDING, month.toString()).stream()
                .filter(pendency -> !pendency.isDeleted())
                .map(pendency -> toDto(pendency, rules))
                .sorted(Comparator.comparing((AuditDTOs.Finding finding) -> !"ABERTA".equals(finding.status()))
                        .thenComparing(finding -> PendencySeverity.valueOf(finding.severity()).ordinal())
                        .thenComparing(AuditDTOs.Finding::ruleCode))
                .toList();
        AuditDTOs.Run run = runRepository.findByCompany_IdAndCompetence(company.getId(), month.toString())
                .map(item -> new AuditDTOs.Run(item.getRanAt(), item.getRanBy(), item.getFindings(), item.getNotesChecked()))
                .orElse(null);
        return new AuditDTOs.Report(company.getId(), company.getName(),
                company.getDocument() == null ? null : company.getDocument().getNumber(), month.toString(), run, findings);
    }

    // ---------------------------------------------------------------- laudo

    @Transactional
    public byte[] csv(String companyId, String competence) {
        AuditDTOs.Report report = report(companyId, competence);
        StringBuilder csv = new StringBuilder("﻿Empresa;CNPJ;Competência;Grupo;Regra;Severidade;Situação da regra;Achado;Detalhe;Situação\n");
        for (AuditDTOs.Finding finding : report.findings()) {
            csv.append(String.join(";", cell(report.companyName()), cell(report.companyDocument()), report.competence(),
                    finding.group(), cell(finding.ruleTitle()), finding.severity(),
                    finding.ruleReviewed() ? "conferida" : "a conferir", cell(finding.title()), cell(finding.detail()),
                    finding.status())).append('\n');
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Transactional
    public byte[] pdf(String companyId, String competence) {
        AuditDTOs.Report report = report(companyId, competence);
        Map<String, AuditRule> rules = catalog();
        Font title = new Font(Font.HELVETICA, 14, Font.BOLD, new Color(32, 80, 162));
        Font heading = new Font(Font.HELVETICA, 10, Font.BOLD);
        Font text = new Font(Font.HELVETICA, 8.5f, Font.NORMAL);
        Font small = new Font(Font.HELVETICA, 7.5f, Font.NORMAL, Color.DARK_GRAY);
        Font cellBold = new Font(Font.HELVETICA, 8, Font.BOLD);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 36, 36, 36, 36);
        PdfWriter.getInstance(document, out);
        document.open();
        document.add(new Paragraph("Laudo da Auditoria tipo malha", title));
        document.add(new Paragraph(report.companyName() + (report.companyDocument() == null ? "" : " - CNPJ " + report.companyDocument())
                + " - competência " + report.competence(), text));
        document.add(new Paragraph(report.run() == null ? "Competência ainda não auditada."
                : "Auditado em " + report.run().ranAt().format(DAY_TIME) + " por " + report.run().ranBy() + " - "
                + report.run().notesChecked() + " nota(s) do mês, " + report.run().findings() + " achado(s).", small));
        document.add(new Paragraph(" "));
        Paragraph warning = new Paragraph("Os achados são indícios para conferência, não infrações. Achados \"a conferir\" vêm de "
                + "regras que o time fiscal ainda não validou.", small);
        document.add(warning);
        document.add(new Paragraph(" "));

        if (report.findings().isEmpty()) {
            document.add(new Paragraph("Nenhum achado na competência.", text));
        } else {
            PdfPTable table = new PdfPTable(new float[]{1.1f, 2.4f, 5.5f, 1.2f});
            table.setWidthPercentage(100);
            for (String header : List.of("Severidade", "Regra", "Achado", "Situação")) {
                PdfPCell cell = new PdfPCell(new Phrase(header, cellBold));
                cell.setBackgroundColor(new Color(238, 241, 246));
                table.addCell(cell);
            }
            for (AuditDTOs.Finding finding : report.findings()) {
                table.addCell(new Phrase(finding.severity(), text));
                table.addCell(new Phrase(finding.ruleTitle() + (finding.ruleReviewed() ? "" : " (a conferir)"), text));
                Phrase detail = new Phrase(finding.title() + "\n", cellBold);
                detail.add(new Phrase(finding.detail() == null ? "" : finding.detail(), text));
                table.addCell(new PdfPCell(detail));
                table.addCell(new Phrase(status(finding.status()), text));
            }
            document.add(table);
        }

        document.add(new Paragraph(" "));
        document.add(new Paragraph("Fundamentos das regras", heading));
        for (AuditRule rule : rules.values()) {
            if (report.findings().stream().anyMatch(finding -> finding.ruleCode().equals(rule.getCode()))) {
                Paragraph basis = new Paragraph(rule.getTitle() + ": " + (rule.getLegalBasis() == null ? "-" : rule.getLegalBasis()), small);
                basis.setAlignment(Element.ALIGN_LEFT);
                document.add(basis);
            }
        }
        document.close();
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- helpers

    private PendencyFinder.Finding toPendency(AuditEngine.Finding finding, AuditRule rule, YearMonth month) {
        String key = "AUDIT:" + rule.getCode() + ":" + month + (finding.keySuffix() == null || finding.keySuffix().isEmpty()
                ? "" : ":" + finding.keySuffix());
        String prefix = rule.isReviewed() ? "Indício: " : "A conferir: ";
        String detail = (finding.detail() == null ? "" : finding.detail() + " ")
                + "Regra: " + rule.getTitle() + (rule.getLegalBasis() == null ? "." : " (" + rule.getLegalBasis() + ").")
                + (rule.isReviewed() ? "" : " Regra ainda não conferida pelo time fiscal.");
        return new PendencyFinder.Finding(PendencyType.AUDIT_FINDING, rule.getSeverity(), key.length() > 150 ? key.substring(0, 150) : key,
                month.toString(), finding.objectType(), finding.objectId(), prefix + finding.title(), detail);
    }

    private static AuditDTOs.Finding toDto(Pendency pendency, Map<String, AuditRule> rules) {
        String[] parts = pendency.getNaturalKey().split(":");
        String code = parts.length > 1 ? parts[1] : "";
        AuditRule rule = rules.get(code);
        return new AuditDTOs.Finding(pendency.getId(), code, rule == null ? code : rule.getTitle(),
                rule == null ? null : rule.getGroup(), pendency.getSeverity().name(), rule != null && rule.isReviewed(),
                pendency.getTitle(), pendency.getDetail(), pendency.getObjectType(), pendency.getObjectId(),
                pendency.getStatus().name(), pendency.getFirstSeenAt(), pendency.getClosedAt(), pendency.getClosedBy(),
                pendency.getCloseComment(), pendency.isAutoClosed());
    }

    private static AuditDTOs.Rule toDto(AuditRule rule) {
        return new AuditDTOs.Rule(rule.getCode(), rule.getTitle(), rule.getGroup(), rule.getSeverity().name(), rule.getLegalBasis(),
                rule.getLink(), rule.getValidFrom(), rule.getValidUntil(), rule.isEnabled(), rule.isReviewed(),
                rule.getReviewedBy(), rule.getReviewedAt());
    }

    private Company company(String companyId) {
        try {
            return companyValidation.checkExist(UUID.fromString(companyId.strip()));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BadRequestException(messageService.getMessage("error.generic.bad.request"));
        }
    }

    /** "2026-10"; blank = the previous month. */
    static YearMonth month(String competence) {
        if (competence == null || competence.isBlank()) {
            return YearMonth.now().minusMonths(1);
        }
        try {
            return YearMonth.parse(competence.strip());
        } catch (DateTimeParseException e) {
            throw new BadRequestException("Competência inválida: use AAAA-MM.");
        }
    }

    private String who() {
        return auditorAware.getCurrentAuditor().orElse(SYSTEM);
    }

    private static String status(String status) {
        return switch (status) {
            case "RESOLVIDA" -> "Resolvido";
            case "DESCONSIDERADA" -> "Desconsiderado";
            default -> "Aberto";
        };
    }

    private static String cell(String value) {
        return value == null ? "" : "\"" + value.replace("\"", "\"\"").replace('\n', ' ') + "\"";
    }

    /** The groups in the order the screen shows them. */
    public static List<String> groups() {
        return Arrays.stream(AuditRuleDefinition.Group.values()).map(Enum::name).toList();
    }
}
