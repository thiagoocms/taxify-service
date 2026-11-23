package br.com.cshub.taxify.service.retention;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.xml.RetentionReview;
import br.com.cshub.taxify.dto.retention.RetentionDTOs;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.repository.RetentionReviewRepository;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The retentions screen (roadmap M1): one company or the whole portfolio, one competence - what
 * the clients must pay (they withheld as takers) and what they can offset (they suffered as
 * providers), with the action and the due date; "marcar como conferida" per line.
 */
@Service
public class RetentionService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final RetentionCalculator calculator;
    private final RetentionReviewRepository reviewRepository;
    private final CompanyRepository companyRepository;
    private final CompanyValidation companyValidation;
    private final TenantAccessService tenantAccessService;
    private final MessageService messageService;
    private final AuditorAware<String> auditorAware;

    public RetentionService(RetentionCalculator calculator, RetentionReviewRepository reviewRepository,
                            CompanyRepository companyRepository, CompanyValidation companyValidation,
                            TenantAccessService tenantAccessService, MessageService messageService,
                            AuditorAware<String> auditorAware) {
        this.calculator = calculator;
        this.reviewRepository = reviewRepository;
        this.companyRepository = companyRepository;
        this.companyValidation = companyValidation;
        this.tenantAccessService = tenantAccessService;
        this.messageService = messageService;
        this.auditorAware = auditorAware;
    }

    /**
     * @param nature A_RECOLHER, A_COMPENSAR, INFORMATIVO or blank for all (the cards always count all)
     * @param search piece of the company or counterpart name, a CNPJ or the note number
     */
    @Transactional(readOnly = true)
    public RetentionDTOs.Screen screen(String companyId, String competence, String nature, String search) {
        YearMonth month = month(competence);
        List<RetentionDTOs.Row> all = rows(scope(companyId), month);
        List<RetentionDTOs.Card> cards = new ArrayList<>();
        for (RetentionCalculator.Nature value : RetentionCalculator.Nature.values()) {
            cards.add(card(value.name(), all));
        }
        String term = search == null || search.isBlank() ? null : search.strip().toLowerCase(Locale.ROOT);
        String digits = term == null ? "" : term.replaceAll("\\D", "");
        List<RetentionDTOs.Row> rows = all.stream()
                .filter(row -> nature == null || nature.isBlank() || row.nature().equals(nature.strip()))
                .filter(row -> term == null || matches(row, term, digits))
                .toList();
        return new RetentionDTOs.Screen(month.toString(), cards, rows);
    }

    /** Same rows as the screen, as CSV (semicolon, Excel pt-BR). */
    @Transactional(readOnly = true)
    public byte[] csv(String companyId, String competence, String nature, String search) {
        StringBuilder csv = new StringBuilder("﻿Empresa;CNPJ;Competência;Natureza;Tributo;Documento;Número;Emissão;"
                + "Contraparte;CNPJ/CPF contraparte;Valor do documento;Valor retido;Vencimento;Ação;Indevida;Conferida\n");
        for (RetentionDTOs.Row row : screen(companyId, competence, nature, search).rows()) {
            csv.append(String.join(";", cell(row.companyName()), cell(row.companyDocument()), row.competence(), row.nature(),
                    row.tax(), row.documentType(), cell(row.numero()), row.issueDate() == null ? "" : row.issueDate().format(DAY),
                    cell(row.counterpartName()), cell(row.counterpartDocument()), money(row.serviceValue()),
                    money(row.amount()), row.dueDate() == null ? "" : row.dueDate().format(DAY), cell(row.action()),
                    row.undue() ? "sim" : "", row.reviewed() ? "sim" : "")).append('\n');
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Marks (or unmarks) retentions as checked - only keys of companies the user can see. */
    @Transactional
    public int review(RetentionDTOs.ReviewRequest request) {
        Map<UUID, Company> companies = scope(request.companyId()).stream()
                .collect(Collectors.toMap(Company::getId, Function.identity()));
        String who = auditorAware.getCurrentAuditor().orElse("sistema");
        LocalDateTime now = LocalDateTime.now();
        int changed = 0;
        for (String compound : new HashSet<>(request.keys())) {
            // "<companyId>|<retention key>": the same note can be in two companies of the portfolio
            int bar = compound.indexOf('|');
            if (bar < 0) {
                continue;
            }
            Company company;
            try {
                company = companies.get(UUID.fromString(compound.substring(0, bar)));
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (company == null) {
                continue;
            }
            String key = compound.substring(bar + 1);
            var existing = reviewRepository.findByCompany_IdAndNaturalKey(company.getId(), key);
            if (request.reviewed() && existing.isEmpty()) {
                RetentionReview review = new RetentionReview();
                review.setCompany(company);
                review.setNaturalKey(key);
                review.setReviewedBy(who);
                review.setReviewedAt(now);
                review.setDeleted(false);
                reviewRepository.save(review);
                changed++;
            } else if (!request.reviewed() && existing.isPresent()) {
                reviewRepository.delete(existing.get());
                changed++;
            }
        }
        return changed;
    }

    private List<RetentionDTOs.Row> rows(List<Company> companies, YearMonth month) {
        RetentionCalculator.Result result = calculator.compute(companies, month);
        Set<String> keys = result.retentions().stream().map(RetentionCalculator.Retention::key).collect(Collectors.toSet());
        Map<String, RetentionReview> reviews = keys.isEmpty() ? Map.of() : reviewRepository
                .findByCompany_IdInAndNaturalKeyIn(companies.stream().map(Company::getId).toList(), keys).stream()
                .collect(Collectors.toMap(review -> review.getCompany().getId() + "|" + review.getNaturalKey(),
                        Function.identity(), (a, b) -> a));
        return result.retentions().stream()
                .map(retention -> toRow(retention, reviews.get(retention.company().getId() + "|" + retention.key())))
                .sorted(Comparator.comparing(RetentionDTOs.Row::companyName, Comparator.nullsLast(String::compareToIgnoreCase))
                        .thenComparing(RetentionDTOs.Row::nature)
                        .thenComparing(RetentionDTOs.Row::issueDate, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private static RetentionDTOs.Row toRow(RetentionCalculator.Retention r, RetentionReview review) {
        Company company = r.company();
        return new RetentionDTOs.Row(company.getId() + "|" + r.key(), company.getId(), company.getName(),
                company.getDocument() == null ? null : company.getDocument().getNumber(), r.documentType(), r.documentId(),
                r.numero(), r.issueDate(), r.competence().toString(), r.counterpartName(), r.counterpartDocument(),
                r.nature().name(), r.tax().name(), r.amount(), r.serviceValue(), r.dueDate(), r.action(), r.undue(),
                review != null, review == null ? null : review.getReviewedBy(), review == null ? null : review.getReviewedAt());
    }

    private static RetentionDTOs.Card card(String nature, List<RetentionDTOs.Row> rows) {
        BigDecimal total = BigDecimal.ZERO;
        Map<String, BigDecimal> byTax = new LinkedHashMap<>();
        Set<String> notes = new HashSet<>();
        long pending = 0;
        for (RetentionDTOs.Row row : rows) {
            if (!row.nature().equals(nature)) {
                continue;
            }
            total = total.add(row.amount());
            byTax.merge(row.tax(), row.amount(), BigDecimal::add);
            notes.add(row.companyId() + ":" + row.documentId());
            if (!row.reviewed()) {
                pending++;
            }
        }
        return new RetentionDTOs.Card(nature, total, notes.size(), byTax, pending);
    }

    private static boolean matches(RetentionDTOs.Row row, String term, String digits) {
        return contains(row.companyName(), term) || contains(row.counterpartName(), term)
                || (!digits.isEmpty() && (contains(row.companyDocument(), digits) || contains(row.counterpartDocument(), digits)))
                || term.equals(row.numero());
    }

    private static boolean contains(String value, String term) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(term);
    }

    /** One company (access checked) or every active company of the user's firm they can see. */
    private List<Company> scope(String companyId) {
        if (companyId != null && !companyId.isBlank()) {
            try {
                return List.of(companyValidation.checkExist(UUID.fromString(companyId.strip())));
            } catch (IllegalArgumentException e) {
                throw new BadRequestException(messageService.getMessage("error.generic.bad.request"));
            }
        }
        UUID firmId = tenantAccessService.getCurrentAccountingFirmId()
                .orElseThrow(() -> new BadRequestException(messageService.getMessage("error.pendency.firm.required")));
        return companyRepository.findByAccountingFirm_IdAndDeletedIsFalseOrderByNameAsc(firmId).stream()
                .filter(company -> !Boolean.FALSE.equals(company.getActive()))
                .filter(company -> tenantAccessService.canAccessCompany(company.getId()))
                .toList();
    }

    /** "2026-10"; blank = the previous month (the one usually being closed). */
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

    private static String cell(String value) {
        return value == null ? "" : "\"" + value.replace("\"", "\"\"").replace('\n', ' ') + "\"";
    }

    private static String money(BigDecimal value) {
        return value == null ? "" : value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }
}
