package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.TaxRegimes;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.company.CompanyTaxRegime;
import br.com.cshub.taxify.dto.company.CompanyTaxRegimeDTO;
import br.com.cshub.taxify.enumerated.BusinessActivity;
import br.com.cshub.taxify.enumerated.SimplesAnnex;
import br.com.cshub.taxify.enumerated.TaxRegime;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.exception.ResourceNotFoundException;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.repository.CompanyTaxRegimeRepository;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Regime history of a company. A month is assessed with the regime in force in it - a
 * company that left the Simples in January keeps its 2025 months as Simples. The company's
 * own regime fields mirror the period in force today.
 */
@Service
public class CompanyTaxRegimeService {

    /** The regime that applies to a month. */
    public record RegimeAt(TaxRegime regime, BusinessActivity activity, SimplesAnnex serviceAnnex, String label) {
    }

    private final CompanyTaxRegimeRepository repository;
    private final CompanyRepository companyRepository;
    private final CompanyValidation companyValidation;
    private final MessageService messageService;

    public CompanyTaxRegimeService(CompanyTaxRegimeRepository repository, CompanyRepository companyRepository,
                                   CompanyValidation companyValidation, MessageService messageService) {
        this.repository = repository;
        this.companyRepository = companyRepository;
        this.companyValidation = companyValidation;
        this.messageService = messageService;
    }

    @Transactional(readOnly = true)
    public List<CompanyTaxRegimeDTO> history(UUID companyId) {
        companyValidation.checkExist(companyId);
        return toDtos(repository.history(companyId));
    }

    /** Adds (or replaces) the period starting at {@code dto.validFrom()}; then the company mirrors today's period. */
    @Transactional
    public List<CompanyTaxRegimeDTO> save(UUID companyId, CompanyTaxRegimeDTO dto) {
        Company company = companyValidation.checkExist(companyId);
        if (dto.taxRegime() == null) {
            throw new BadRequestException(messageService.getMessage("error.taxRegime.required"));
        }
        LocalDate validFrom = parseMonth(dto.validFrom());
        upsert(company, repository.history(companyId), validFrom, dto.taxRegime(), dto.businessActivity(), serviceAnnex(dto.taxRegime(), dto.simplesServiceAnnex()));
        mirrorCurrent(company);
        return toDtos(repository.history(companyId));
    }

    @Transactional
    public List<CompanyTaxRegimeDTO> delete(UUID companyId, UUID regimeId) {
        Company company = companyValidation.checkExist(companyId);
        CompanyTaxRegime regime = repository.findFirstByIdAndDeletedIsFalse(regimeId)
                .filter(r -> r.getCompany().getId().equals(companyId))
                .orElseThrow(() -> new ResourceNotFoundException(messageService.getMessage("error.taxRegime.not.found")));
        regime.setDeleted(true);
        repository.save(regime);
        mirrorCurrent(company);
        return toDtos(repository.history(companyId));
    }

    /**
     * After the company's regime fields were edited (form, Acessórias import): records the
     * change as a period from {@code from} (null = the current month). The first regime a
     * company gets is "since always". Nothing when the fields match the period in force.
     */
    @Transactional
    public void recordChange(Company company, YearMonth from) {
        if (company.getTaxRegimeType() == null) {
            return;
        }
        List<CompanyTaxRegime> history = repository.history(company.getId());
        CompanyTaxRegime current = periodAt(history, YearMonth.now());
        if (current != null && current.sameRegimeAs(company.getTaxRegimeType(), company.getBusinessActivity(), company.getSimplesServiceAnnex())) {
            return;
        }
        LocalDate validFrom = history.isEmpty() ? null : (from == null ? YearMonth.now() : from).atDay(1);
        upsert(company, history, validFrom, company.getTaxRegimeType(), company.getBusinessActivity(), company.getSimplesServiceAnnex());
    }

    /** The regime of each company in a month; a company without history falls back to its own fields. */
    @Transactional(readOnly = true)
    public Map<UUID, RegimeAt> regimesAt(Collection<Company> companies, YearMonth month) {
        Map<UUID, List<CompanyTaxRegime>> byCompany = new HashMap<>();
        if (!companies.isEmpty()) {
            for (CompanyTaxRegime regime : repository.historyOf(companies.stream().map(Company::getId).toList())) {
                byCompany.computeIfAbsent(regime.getCompany().getId(), id -> new ArrayList<>()).add(regime);
            }
        }
        Map<UUID, RegimeAt> result = new HashMap<>();
        for (Company company : companies) {
            CompanyTaxRegime period = periodAt(byCompany.getOrDefault(company.getId(), List.of()), month);
            result.put(company.getId(), period == null
                    ? new RegimeAt(company.getTaxRegimeType(), company.getBusinessActivity(), company.getSimplesServiceAnnex(), company.getTaxRegime())
                    : new RegimeAt(period.getTaxRegime(), period.getBusinessActivity(), period.getSimplesServiceAnnex(),
                    TaxRegimes.label(period.getTaxRegime(), period.getBusinessActivity())));
        }
        return result;
    }

    // ------------------------------------------------------------------ internals

    /** Last period started on or before the month (history is oldest first). */
    static CompanyTaxRegime periodAt(List<CompanyTaxRegime> history, YearMonth month) {
        LocalDate day = month.atDay(1);
        CompanyTaxRegime found = null;
        for (CompanyTaxRegime regime : history) {
            if (regime.getValidFrom() == null || !regime.getValidFrom().isAfter(day)) {
                found = regime;
            }
        }
        return found;
    }

    private void upsert(Company company, List<CompanyTaxRegime> history, LocalDate validFrom, TaxRegime regime,
                        BusinessActivity activity, SimplesAnnex annex) {
        CompanyTaxRegime period = history.stream()
                .filter(r -> Objects.equals(r.getValidFrom(), validFrom))
                .findFirst()
                .orElseGet(() -> {
                    CompanyTaxRegime created = new CompanyTaxRegime();
                    created.setCompany(company);
                    created.setValidFrom(validFrom);
                    return created;
                });
        period.setTaxRegime(regime);
        period.setBusinessActivity(activity);
        period.setSimplesServiceAnnex(annex);
        repository.save(period);
    }

    /** The company's fields = the period in force today (a period dated in the future doesn't count yet). */
    private void mirrorCurrent(Company company) {
        CompanyTaxRegime current = periodAt(repository.history(company.getId()), YearMonth.now());
        if (current == null) {
            return;
        }
        company.applyStructuredRegime(current.getTaxRegime(), current.getBusinessActivity(), current.getSimplesServiceAnnex());
        companyRepository.save(company);
    }

    private static SimplesAnnex serviceAnnex(TaxRegime regime, SimplesAnnex annex) {
        return regime == TaxRegime.SIMPLES_NACIONAL ? annex : null;
    }

    private List<CompanyTaxRegimeDTO> toDtos(List<CompanyTaxRegime> history) {
        List<CompanyTaxRegimeDTO> result = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            CompanyTaxRegime regime = history.get(i);
            LocalDate next = i + 1 < history.size() ? history.get(i + 1).getValidFrom() : null;
            result.add(new CompanyTaxRegimeDTO(
                    regime.getId().toString(),
                    regime.getTaxRegime(),
                    regime.getBusinessActivity(),
                    regime.getSimplesServiceAnnex(),
                    regime.getValidFrom() == null ? null : YearMonth.from(regime.getValidFrom()).toString(),
                    next == null ? null : YearMonth.from(next).minusMonths(1).toString(),
                    TaxRegimes.label(regime.getTaxRegime(), regime.getBusinessActivity())));
        }
        return result;
    }

    /** "2026-01" → 2026-01-01; blank = since always. */
    private LocalDate parseMonth(String month) {
        if (month == null || month.isBlank()) {
            return null;
        }
        try {
            return YearMonth.parse(month.strip()).atDay(1);
        } catch (DateTimeParseException e) {
            throw new BadRequestException(messageService.getMessage("error.assessment.month.invalid"));
        }
    }

    /** "2026-01" or null - for the company form's "vigente a partir de". */
    public static YearMonth monthOrNull(String month) {
        try {
            return month == null || month.isBlank() ? null : YearMonth.parse(month.strip());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
