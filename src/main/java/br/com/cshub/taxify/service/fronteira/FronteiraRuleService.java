package br.com.cshub.taxify.service.fronteira;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.fronteira.FronteiraRule;
import br.com.cshub.taxify.dto.fronteira.FronteiraRuleDTOs;
import br.com.cshub.taxify.enumerated.FronteiraRuleKind;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.exception.ResourceNotFoundException;
import br.com.cshub.taxify.repository.FronteiraRuleRepository;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The Fronteira PE table: the standard calculation's rates (internal rate, ST MVA, Simples percentage)
 * and the accountant's own formulas, global or of one company. A changed rule goes back to "a conferir";
 * only the fiscal team marks it as checked. The modal internal rate of PE is created on first use, a
 * conferir.
 */
@Service
public class FronteiraRuleService {

    /** From the reference the accountant shared (Lei 15.730/2016) - created "a conferir". */
    static final BigDecimal MODAL_RATE = new BigDecimal("20.5");

    private static final String SYSTEM = "sistema";

    private final FronteiraRuleRepository repository;
    private final CompanyValidation companyValidation;
    private final MessageService messageService;
    private final AuditorAware<String> auditorAware;

    public FronteiraRuleService(FronteiraRuleRepository repository, CompanyValidation companyValidation,
                                MessageService messageService, AuditorAware<String> auditorAware) {
        this.repository = repository;
        this.companyValidation = companyValidation;
        this.messageService = messageService;
        this.auditorAware = auditorAware;
    }

    /** What the calculation of a company uses (global + its own), with the modal rate in place. */
    @Transactional
    public List<FronteiraRule> rulesFor(UUID companyId) {
        ensureDefaults();
        return repository.findForCompany(companyId);
    }

    @Transactional
    public List<FronteiraRuleDTOs.Rule> list(String companyId) {
        ensureDefaults();
        List<FronteiraRule> rules = companyId == null || companyId.isBlank()
                ? repository.findGlobal()
                : repository.findForCompany(company(companyId).getId());
        return rules.stream().map(FronteiraRuleService::toDto).toList();
    }

    /** {@code global}: the endpoint for rules of every company (another permission) - the input's companyId must match it. */
    @Transactional
    public FronteiraRuleDTOs.Rule create(FronteiraRuleDTOs.RuleInput input, boolean global) {
        FronteiraRule rule = new FronteiraRule();
        rule.setDeleted(false);
        rule.setEnabled(true);
        rule.setCompany(scope(input, global));
        apply(rule, input);
        return toDto(repository.save(rule));
    }

    @Transactional
    public FronteiraRuleDTOs.Rule update(UUID id, FronteiraRuleDTOs.RuleInput input, boolean global) {
        FronteiraRule rule = find(id, global);
        apply(rule, input);
        // what it calculates changed: back to "a conferir"
        rule.setReviewedAt(null);
        rule.setReviewedBy(null);
        return toDto(repository.save(rule));
    }

    @Transactional
    public void delete(UUID id, boolean global) {
        FronteiraRule rule = find(id, global);
        rule.setDeleted(true);
        repository.save(rule);
    }

    @Transactional
    public FronteiraRuleDTOs.Rule review(UUID id, boolean reviewed) {
        FronteiraRule rule = find(id, null);
        rule.setReviewedAt(reviewed ? LocalDateTime.now() : null);
        rule.setReviewedBy(reviewed ? auditorAware.getCurrentAuditor().orElse(SYSTEM) : null);
        return toDto(repository.save(rule));
    }

    @Transactional
    public FronteiraRuleDTOs.Rule enable(UUID id, boolean enabled) {
        FronteiraRule rule = find(id, null);
        rule.setEnabled(enabled);
        return toDto(repository.save(rule));
    }

    public FronteiraRuleDTOs.FormulaCheck check(String formula) {
        try {
            FormulaEvaluator.validate(formula);
            return new FronteiraRuleDTOs.FormulaCheck(true, null, FormulaEvaluator.VARIABLES);
        } catch (FormulaEvaluator.FormulaException e) {
            return new FronteiraRuleDTOs.FormulaCheck(false, e.getMessage(), FormulaEvaluator.VARIABLES);
        }
    }

    // ---------------------------------------------------------------- helpers

    private void ensureDefaults() {
        if (!repository.existsByKindAndCompanyIsNullAndNcmIsNull(FronteiraRuleKind.ALIQUOTA_INTERNA)) {
            FronteiraRule modal = new FronteiraRule();
            modal.setKind(FronteiraRuleKind.ALIQUOTA_INTERNA);
            modal.setDescricao("Alíquota modal de PE");
            modal.setAliquota(MODAL_RATE);
            modal.setFundamento("Lei 15.730/2016 - alíquota interna modal de PE (a conferir)");
            modal.setEnabled(true);
            modal.setDeleted(false);
            repository.save(modal);
        }
    }

    private Company scope(FronteiraRuleDTOs.RuleInput input, boolean global) {
        if (global) {
            if (input.companyId() != null) {
                throw invalid("Uma regra geral vale para todas as empresas - sem empresa.");
            }
            return null;
        }
        if (input.companyId() == null) {
            throw invalid("Escolha a empresa da regra.");
        }
        return company(input.companyId().toString());
    }

    /** {@code global} null = either scope (review, enable). */
    private FronteiraRule find(UUID id, Boolean global) {
        FronteiraRule rule = repository.findById(id).filter(found -> !found.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException(messageService.getMessage("error.entity.not.found")));
        if (rule.getCompany() != null) {
            companyValidation.checkExist(rule.getCompany().getId()); // tenant access
        }
        if (global != null && global != (rule.getCompany() == null)) {
            throw invalid(global ? "Esta regra é de uma empresa." : "Esta regra é geral - altere pela tabela geral.");
        }
        return rule;
    }

    private void apply(FronteiraRule rule, FronteiraRuleDTOs.RuleInput input) {
        if (input.kind() == null) {
            throw invalid("Escolha o tipo da regra.");
        }
        rule.setKind(input.kind());
        rule.setDescricao(text(input.descricao(), 255));
        rule.setNcm(digitsOrNull(input.ncm(), 10));
        rule.setCest(digitsOrNull(input.cest(), 10));
        rule.setCodigoProduto(text(input.codigoProduto(), 60));
        rule.setCnpjFornecedor(digitsOrNull(input.cnpjFornecedor(), 14));
        rule.setFundamento(text(input.fundamento(), 500));
        rule.setLink(text(input.link(), 500));
        rule.setValidFrom(input.validFrom());
        rule.setValidUntil(input.validUntil());
        if (input.validFrom() != null && input.validUntil() != null && input.validUntil().isBefore(input.validFrom())) {
            throw invalid("A vigência termina antes de começar.");
        }
        rule.setAliquota(null);
        rule.setMva(null);
        rule.setPercentual(null);
        rule.setFormula(null);
        rule.setAplicaEm(null);
        switch (input.kind()) {
            case ALIQUOTA_INTERNA -> rule.setAliquota(percent(input.aliquota(), "a alíquota interna"));
            case ST_MVA -> {
                if (rule.getNcm() == null && rule.getCest() == null && rule.getCodigoProduto() == null) {
                    throw invalid("Uma regra de ST precisa de NCM, CEST ou código do produto.");
                }
                if (input.mva() == null || input.mva().signum() < 0 || input.mva().compareTo(new BigDecimal("1000")) > 0) {
                    throw invalid("Informe a MVA original em % (de 0 a 1000).");
                }
                rule.setMva(input.mva());
            }
            case SIMPLES_PERCENTUAL -> rule.setPercentual(percent(input.percentual(), "a carga líquida"));
            case FORMULA -> {
                try {
                    FormulaEvaluator.validate(input.formula());
                } catch (FormulaEvaluator.FormulaException e) {
                    throw invalid(e.getMessage());
                }
                rule.setFormula(input.formula().strip());
                rule.setAplicaEm(aplicaEm(input.aplicaEm()));
            }
        }
    }

    /** Blank or TODOS = any standard treatment; devolução, remessa and ST already withheld can't be replaced. */
    private String aplicaEm(String value) {
        if (value == null || value.isBlank() || "TODOS".equalsIgnoreCase(value.strip())) {
            return null;
        }
        String treatment = value.strip().toUpperCase(java.util.Locale.ROOT);
        if (!REPLACEABLE.contains(treatment)) {
            throw invalid("A fórmula pode substituir: sem encerramento, ST, carga líquida ou uso e consumo.");
        }
        return treatment;
    }

    private static final java.util.Set<String> REPLACEABLE = java.util.Set.of(
            FronteiraCalculator.Treatment.SEM_ENCERRAMENTO.name(), FronteiraCalculator.Treatment.ST.name(),
            FronteiraCalculator.Treatment.CARGA_LIQUIDA.name(), FronteiraCalculator.Treatment.USO_CONSUMO.name());

    private BigDecimal percent(BigDecimal value, String what) {
        if (value == null || value.signum() < 0 || value.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw invalid("Informe " + what + " em % (de 0 a 100).");
        }
        return value;
    }

    private BadRequestException invalid(String detail) {
        return new BadRequestException(messageService.getMessage("error.fronteira.rule.invalid", detail));
    }

    private Company company(String companyId) {
        try {
            return companyValidation.checkExist(UUID.fromString(companyId.strip()));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(messageService.getMessage("error.generic.bad.request"));
        }
    }

    private static String text(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        return stripped.length() > max ? stripped.substring(0, max) : stripped;
    }

    private static String digitsOrNull(String value, int max) {
        String digits = value == null ? "" : value.replaceAll("\\D", "");
        return digits.isEmpty() ? null : digits.length() > max ? digits.substring(0, max) : digits;
    }

    static FronteiraRuleDTOs.Rule toDto(FronteiraRule rule) {
        return new FronteiraRuleDTOs.Rule(rule.getId(), rule.getCompany() == null ? null : rule.getCompany().getId(), rule.getKind(),
                rule.getDescricao(), rule.getNcm(), rule.getCest(), rule.getCodigoProduto(), rule.getCnpjFornecedor(), rule.getAliquota(),
                rule.getMva(), rule.getPercentual(), rule.getFormula(), rule.getAplicaEm(), rule.getFundamento(), rule.getLink(), rule.getValidFrom(),
                rule.getValidUntil(), rule.isEnabled(), rule.isReviewed(), rule.getReviewedBy(), rule.getReviewedAt(),
                rule.getCreatedBy(), rule.getCreatedDate());
    }
}
