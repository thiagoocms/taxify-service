package br.com.cshub.taxify.service.pendency;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.pendency.Pendency;
import br.com.cshub.taxify.enumerated.PendencySeverity;
import br.com.cshub.taxify.enumerated.PendencyStatus;
import br.com.cshub.taxify.enumerated.PendencyType;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.repository.CaptureRunRepository;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.repository.PendencyRepository;
import br.com.cshub.taxify.repository.SefazDistControlRepository;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PendencyServiceTest {

    @Mock
    private PendencyRepository repository;
    @Mock
    private PendencyFinder finder;
    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private CompanyValidation companyValidation;
    @Mock
    private TenantAccessService tenantAccessService;
    @Mock
    private SefazDistControlRepository controlRepository;
    @Mock
    private CaptureRunRepository captureRunRepository;
    @Mock
    private MessageService messageService;

    private PendencyService service;
    private Company company;
    private final List<Pendency> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new PendencyService(repository, finder, companyRepository, companyValidation, tenantAccessService,
                controlRepository, captureRunRepository, messageService, () -> Optional.of("ana"),
                org.mockito.Mockito.mock(br.com.cshub.taxify.repository.NfseAdnControlRepository.class));
        company = new Company(UUID.randomUUID());
        company.setName("EMPRESA");
        when(repository.findByCompany_IdAndTypeIn(eq(company.getId()), any())).thenReturn(stored);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(messageService.getMessage(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static PendencyFinder.Finding finding(PendencyType type, String key) {
        return new PendencyFinder.Finding(type, type.defaultSeverity, type.name() + ":" + key, "2026-11",
                Pendency.OBJECT_COMPANY, "x", "Título " + key, "Detalhe");
    }

    private Pendency existing(String key, PendencyStatus status) {
        Pendency pendency = new Pendency();
        pendency.setId(UUID.randomUUID());
        pendency.setCompany(company);
        pendency.setType(PendencyType.CERTIFICATE_EXPIRING);
        pendency.setSeverity(PendencySeverity.MEDIA);
        pendency.setNaturalKey("CERTIFICATE_EXPIRING:" + key);
        pendency.setStatus(status);
        pendency.setFirstSeenAt(LocalDateTime.now().minusDays(3));
        pendency.setLastSeenAt(LocalDateTime.now().minusHours(1));
        stored.add(pendency);
        return pendency;
    }

    @SuppressWarnings("unchecked")
    private List<Pendency> saved() {
        ArgumentCaptor<List<Pendency>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        return captor.getValue();
    }

    @Test
    void aNewCauseOpensAPendency() {
        int open = service.sync(company, List.of(finding(PendencyType.REGIME_MISSING, "1")));

        assertThat(open).isEqualTo(1);
        Pendency created = saved().getFirst();
        assertThat(created.getStatus()).isEqualTo(PendencyStatus.ABERTA);
        assertThat(created.getType()).isEqualTo(PendencyType.REGIME_MISSING);
        assertThat(created.getCompetence()).isEqualTo("2026-11");
        assertThat(created.getFirstSeenAt()).isNotNull();
    }

    @Test
    void aKnownCauseIsRefreshedNotDuplicated() {
        Pendency known = existing("c1", PendencyStatus.ABERTA);
        LocalDateTime firstSeen = known.getFirstSeenAt();

        service.sync(company, List.of(finding(PendencyType.CERTIFICATE_EXPIRING, "c1"), finding(PendencyType.CERTIFICATE_EXPIRING, "c1")));

        assertThat(saved()).containsExactly(known);
        assertThat(known.getTitle()).isEqualTo("Título c1");
        assertThat(known.getFirstSeenAt()).isEqualTo(firstSeen);
        assertThat(known.getLastSeenAt()).isAfter(firstSeen);
    }

    @Test
    void aCauseThatWentAwayClosesItsPendencyBySelf() {
        Pendency gone = existing("c1", PendencyStatus.ABERTA);

        int open = service.sync(company, List.of());

        assertThat(open).isZero();
        assertThat(gone.getStatus()).isEqualTo(PendencyStatus.RESOLVIDA);
        assertThat(gone.isAutoClosed()).isTrue();
        assertThat(gone.getClosedBy()).isEqualTo("sistema");
        assertThat(gone.getAbsentSince()).isNotNull();
    }

    @Test
    void aDismissedPendencyStaysClosedWhileItsCauseIsThere() {
        Pendency dismissed = existing("c1", PendencyStatus.DESCONSIDERADA);

        service.sync(company, List.of(finding(PendencyType.CERTIFICATE_EXPIRING, "c1")));

        assertThat(dismissed.getStatus()).isEqualTo(PendencyStatus.DESCONSIDERADA);
    }

    @Test
    void aClosedPendencyWhoseCauseCameBackIsReopened() {
        Pendency closed = existing("c1", PendencyStatus.RESOLVIDA);
        closed.setAbsentSince(LocalDateTime.now().minusDays(1));
        closed.setCloseComment("renovado");

        service.sync(company, List.of(finding(PendencyType.CERTIFICATE_EXPIRING, "c1")));

        assertThat(closed.getStatus()).isEqualTo(PendencyStatus.ABERTA);
        assertThat(closed.getCloseComment()).isNull();
        assertThat(closed.getAbsentSince()).isNull();
    }

    @Test
    void closingNeedsAccessAndKeepsWhoAndWhy() {
        Pendency open = existing("c1", PendencyStatus.ABERTA);
        when(repository.findById(open.getId())).thenReturn(Optional.of(open));

        service.close(open.getId(), PendencyStatus.DESCONSIDERADA, "  cliente encerrou as atividades ");

        verify(tenantAccessService).assertCompanyAccess(company);
        assertThat(open.getStatus()).isEqualTo(PendencyStatus.DESCONSIDERADA);
        assertThat(open.getClosedBy()).isEqualTo("ana");
        assertThat(open.getCloseComment()).isEqualTo("cliente encerrou as atividades");
        assertThat(open.isAutoClosed()).isFalse();
        assertThatThrownBy(() -> service.close(open.getId(), PendencyStatus.ABERTA, "x"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void thePortfolioNeedsAFirmUser() {
        when(tenantAccessService.getCurrentAccountingFirmId()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.summary(null)).isInstanceOf(BadRequestException.class)
                .hasMessage("error.pendency.firm.required");
    }

    @Test
    void countersAddUpPerTypeAndSeverity() {
        UUID firm = UUID.randomUUID();
        when(tenantAccessService.getCurrentAccountingFirmId()).thenReturn(Optional.of(firm));
        when(tenantAccessService.accessibleCompanyIds(List.of(company.getId()))).thenReturn(List.of(company.getId()));
        when(companyRepository.findByAccountingFirm_IdAndDeletedIsFalseOrderByNameAsc(firm)).thenReturn(List.of(company));
        when(repository.countOpen(List.of(company.getId()))).thenReturn(List.of(
                new Object[]{PendencyType.SOURCE_ONLY_SIEG, PendencySeverity.BAIXA, 7L},
                new Object[]{PendencyType.CERTIFICATE_EXPIRED, PendencySeverity.ALTA, 1L}));

        var summary = service.summary(null);

        assertThat(summary.totalOpen()).isEqualTo(8);
        assertThat(summary.counters().getFirst().type()).isEqualTo("CERTIFICATE_EXPIRED"); // high severity first
        assertThat(summary.counters().get(1).baixa()).isEqualTo(7);
    }
}
