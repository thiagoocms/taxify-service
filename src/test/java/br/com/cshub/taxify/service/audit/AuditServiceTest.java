package br.com.cshub.taxify.service.audit;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.audit.AuditRule;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.pendency.Pendency;
import br.com.cshub.taxify.enumerated.PendencyType;
import br.com.cshub.taxify.repository.AuditRuleRepository;
import br.com.cshub.taxify.repository.AuditRunRepository;
import br.com.cshub.taxify.repository.PendencyRepository;
import br.com.cshub.taxify.service.pendency.PendencyFinder;
import br.com.cshub.taxify.service.pendency.PendencyService;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuditServiceTest {

    private static final YearMonth MONTH = YearMonth.of(2026, 10);

    @Mock
    private AuditEngine engine;
    @Mock
    private AuditRuleRepository ruleRepository;
    @Mock
    private AuditRunRepository runRepository;
    @Mock
    private PendencyRepository pendencyRepository;
    @Mock
    private PendencyService pendencyService;
    @Mock
    private CompanyValidation companyValidation;
    @Mock
    private MessageService messageService;

    private AuditService service;
    @Mock
    private br.com.cshub.taxify.common.PlatformAdminService platformAdminService;
    private Company company;
    private final List<AuditRule> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new AuditService(engine, ruleRepository, runRepository, pendencyRepository, pendencyService, companyValidation,
                messageService, () -> Optional.of("fiscal"), platformAdminService);
        company = new Company(UUID.randomUUID());
        when(ruleRepository.findAll()).thenReturn(stored);
        when(ruleRepository.save(any())).thenAnswer(invocation -> {
            AuditRule rule = invocation.getArgument(0);
            if (!stored.contains(rule)) {
                stored.add(rule);
            }
            return rule;
        });
        when(runRepository.findByCompany_IdAndCompetence(any(), any())).thenReturn(Optional.empty());
    }

    @SuppressWarnings("unchecked")
    private List<PendencyFinder.Finding> synced() {
        ArgumentCaptor<List<PendencyFinder.Finding>> captor = ArgumentCaptor.forClass(List.class);
        verify(pendencyService).syncScoped(eq(company), eq(Set.of(PendencyType.AUDIT_FINDING)), eq("2026-10"), captor.capture());
        return captor.getValue();
    }

    @Test
    void theCatalogIsCreatedFromTheCodeOnFirstUse() {
        assertThat(service.rules()).hasSize(AuditRuleDefinition.values().length);
        assertThat(stored).extracting(AuditRule::getCode).contains("NUMERACAO_LACUNA", "IBSCBS_GRUPO");
        assertThat(stored.stream().filter(rule -> rule.getCode().equals("IBSCBS_GRUPO")).findFirst().orElseThrow().getValidFrom())
                .isEqualTo(LocalDate.of(2026, 1, 1));
    }

    @Test
    void anUncheckedRuleOnlyGivesAConferirFindingsAndACheckedOneGivesIndications() {
        when(engine.audit(company, MONTH)).thenReturn(new AuditEngine.Result(List.of(
                new AuditEngine.Finding(AuditRuleDefinition.NUMERO_DUPLICADO, "55|1:13", Pendency.OBJECT_NOTE, "id", "nº 13 duplicado", "Chaves: A, B."),
                new AuditEngine.Finding(AuditRuleDefinition.CFOP_DESTINO, "", Pendency.OBJECT_COMPANY, null, "2 notas", "Ex.")), 10));
        service.rules();
        AuditRule checked = stored.stream().filter(rule -> rule.getCode().equals("CFOP_DESTINO")).findFirst().orElseThrow();
        checked.setReviewedAt(LocalDateTime.now());

        service.audit(company, MONTH, "fiscal");

        List<PendencyFinder.Finding> findings = synced();
        PendencyFinder.Finding duplicated = findings.stream().filter(f -> f.naturalKey().startsWith("AUDIT:NUMERO_DUPLICADO")).findFirst().orElseThrow();
        assertThat(duplicated.title()).isEqualTo("A conferir: nº 13 duplicado");
        assertThat(duplicated.naturalKey()).isEqualTo("AUDIT:NUMERO_DUPLICADO:2026-10:55|1:13");
        assertThat(duplicated.detail()).contains("Regra ainda não conferida");
        assertThat(duplicated.competence()).isEqualTo("2026-10");
        PendencyFinder.Finding cfop = findings.stream().filter(f -> f.naturalKey().startsWith("AUDIT:CFOP_DESTINO")).findFirst().orElseThrow();
        assertThat(cfop.title()).startsWith("Indício: ");
        assertThat(cfop.type()).isEqualTo(PendencyType.AUDIT_FINDING);
    }

    @Test
    void aDisabledOrNotYetValidRuleIsLeftOut() {
        when(engine.audit(company, MONTH)).thenReturn(new AuditEngine.Result(List.of(
                new AuditEngine.Finding(AuditRuleDefinition.NFCE_CANCELAMENTO, "", Pendency.OBJECT_COMPANY, null, "x", "y"),
                new AuditEngine.Finding(AuditRuleDefinition.IBSCBS_GRUPO, "", Pendency.OBJECT_COMPANY, null, "x", "y")), 3));
        service.rules();
        stored.stream().filter(rule -> rule.getCode().equals("NFCE_CANCELAMENTO")).findFirst().orElseThrow().setEnabled(false);
        stored.stream().filter(rule -> rule.getCode().equals("IBSCBS_GRUPO")).findFirst().orElseThrow()
                .setValidFrom(LocalDate.of(2027, 1, 1));

        service.audit(company, MONTH, "fiscal");

        assertThat(synced()).isEmpty();
    }

    @Test
    void reviewingARuleKeepsWhoAndWhen() {
        var rule = service.review("crt_cst", true);

        assertThat(rule.reviewed()).isTrue();
        assertThat(rule.reviewedBy()).isEqualTo("fiscal");
        assertThat(service.review("CRT_CST", false).reviewed()).isFalse();
    }

    @Test
    void onlyThePlatformAdminChangesTheRulesEveryFirmUses() {
        org.mockito.Mockito.doThrow(new org.springframework.security.access.AccessDeniedException("só a CS Hub"))
                .when(platformAdminService).assertCanManageGlobalRules();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.review("crt_cst", true))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.enable("crt_cst", false))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        org.mockito.Mockito.verify(ruleRepository, org.mockito.Mockito.never()).save(any());
    }
}
