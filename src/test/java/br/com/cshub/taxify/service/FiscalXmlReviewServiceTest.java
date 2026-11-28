package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.CurrentUserService;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocument;
import br.com.cshub.taxify.support.TestNotes;
import br.com.cshub.taxify.enumerated.ReviewStatus;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.exception.ResourceNotFoundException;
import br.com.cshub.taxify.repository.FiscalDocumentRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FiscalXmlReviewServiceTest {

    private final FiscalDocumentRepository repository = mock(FiscalDocumentRepository.class);
    private final TenantAccessService tenantAccessService = mock(TenantAccessService.class);
    private final CurrentUserService currentUserService = mock(CurrentUserService.class);
    private final MessageService messageService = mock(MessageService.class);
    private final InvoiceAuditTrail auditTrail = mock(InvoiceAuditTrail.class);
    private final FiscalXmlReviewService service = new FiscalXmlReviewService(repository, tenantAccessService, currentUserService, messageService, auditTrail);

    private FiscalDocument note() {
        return TestNotes.note(TestNotes.company(TestNotes.COMPANY_CNPJ)).withoutKey().build();
    }

    @Test
    void reviewRecordsWhoAndWhen() {
        FiscalDocument note = note();
        User user = new User();
        user.setLogin("maria");
        when(currentUserService.getCurrentUser()).thenReturn(Optional.of(user));
        when(repository.findXmlDocuments(any())).thenReturn(List.of(note));

        assertThat(service.review(List.of(note.getId()), ReviewStatus.REVIEWED, null)).isEqualTo(1);

        assertThat(note.getReviewStatus()).isEqualTo(ReviewStatus.REVIEWED);
        assertThat(note.getReviewedBy()).isEqualTo("maria");
        assertThat(note.getReviewedAt()).isNotNull();
        verify(tenantAccessService).assertCompanyAccess(note.getCompany());
        verify(auditTrail).record(note.getCompany(), note.getId().toString(), br.com.cshub.taxify.enumerated.InvoiceAuditStatus.OK, null);
    }

    @Test
    void backToPendingClearsWhoAndWhen() {
        FiscalDocument note = note();
        note.review(ReviewStatus.REVIEWED, "maria", null, java.time.LocalDateTime.now());
        when(repository.findXmlDocuments(any())).thenReturn(List.of(note));
        when(currentUserService.getCurrentUser()).thenReturn(Optional.empty());

        service.review(List.of(note.getId()), ReviewStatus.PENDING, null);

        assertThat(note.getReviewStatus()).isEqualTo(ReviewStatus.PENDING);
        assertThat(note.getReviewedBy()).isNull();
        assertThat(note.getReviewedAt()).isNull();
    }

    @Test
    void divergenceNeedsANote() {
        when(messageService.getMessage(anyString())).thenReturn("msg");

        assertThatThrownBy(() -> service.review(List.of(UUID.randomUUID()), ReviewStatus.DIVERGENT, " "))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void anUnknownNoteFailsTheWholeCall() {
        when(messageService.getMessage(anyString())).thenReturn("msg");
        when(repository.findXmlDocuments(any())).thenReturn(List.of(note()));

        assertThatThrownBy(() -> service.review(List.of(UUID.randomUUID(), UUID.randomUUID()), ReviewStatus.REVIEWED, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
