package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.document.Document;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import br.com.cshub.taxify.domain.xml.FiscalXmlSyncControl;
import br.com.cshub.taxify.dto.fiscalrule.FiscalXmlRectificationResultDTO;
import br.com.cshub.taxify.enumerated.DocumentTypeEnum;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlRectificationProperties;
import br.com.cshub.taxify.repository.FiscalXmlDocumentRepository;
import br.com.cshub.taxify.repository.FiscalXmlSyncControlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FiscalXmlRectificationSchedulerTest {

    @Mock
    private FiscalXmlSyncControlRepository syncControlRepository;
    @Mock
    private FiscalXmlDocumentRepository documentRepository;
    @Mock
    private FiscalXmlRectificationService rectificationService;

    private FiscalXmlRectificationProperties properties;
    private FiscalXmlRectificationScheduler scheduler;

    @BeforeEach
    void setUp() {
        properties = new FiscalXmlRectificationProperties();
        scheduler = new FiscalXmlRectificationScheduler(syncControlRepository, documentRepository, rectificationService, properties);
    }

    @Test
    void doesNothingWhenDisabled() {
        properties.setEnabled(false);

        scheduler.rectifyAllCompanies();

        verifyNoInteractions(syncControlRepository, documentRepository, rectificationService);
    }

    @Test
    void onlyRectifiesCompaniesThatAreFullySyncedWithSieg() {
        Company fullySynced = companyWith(DocumentTypeEnum.CNPJ);
        when(syncControlRepository.findAllWithCompany()).thenReturn(List.of(controlFor(fullySynced, 200, 200)));
        when(documentRepository.findByCompany_IdAndIssueDateBetweenOrderByIssueDateDesc(eq(fullySynced.getId()), any(), any()))
                .thenReturn(List.of());

        scheduler.rectifyAllCompanies();

        verify(documentRepository).findByCompany_IdAndIssueDateBetweenOrderByIssueDateDesc(eq(fullySynced.getId()), any(), any());
    }

    @Test
    void excludesACompanyStillMidImport() {
        Company midImport = companyWith(DocumentTypeEnum.CNPJ);
        when(syncControlRepository.findAllWithCompany()).thenReturn(List.of(controlFor(midImport, 200, 100)));

        scheduler.rectifyAllCompanies();

        verifyNoInteractions(documentRepository, rectificationService);
    }

    @Test
    void excludesACompanyNeverSuccessfullyChecked() {
        Company neverChecked = companyWith(DocumentTypeEnum.CNPJ);
        when(syncControlRepository.findAllWithCompany()).thenReturn(List.of(controlFor(neverChecked, 0, 0)));

        scheduler.rectifyAllCompanies();

        verifyNoInteractions(documentRepository, rectificationService);
    }

    @Test
    void rectifiesEveryDocumentReturnedForAnEligibleCompany() {
        Company company = companyWith(DocumentTypeEnum.CNPJ);
        when(syncControlRepository.findAllWithCompany()).thenReturn(List.of(controlFor(company, 2, 2)));

        FiscalXmlDocument docOne = new FiscalXmlDocument();
        docOne.setId(UUID.randomUUID());
        FiscalXmlDocument docTwo = new FiscalXmlDocument();
        docTwo.setId(UUID.randomUUID());
        when(documentRepository.findByCompany_IdAndIssueDateBetweenOrderByIssueDateDesc(eq(company.getId()), any(), any()))
                .thenReturn(List.of(docOne, docTwo));

        when(rectificationService.rectify(any()))
                .thenReturn(new FiscalXmlRectificationResultDTO(null, null, null, null, List.of()));

        scheduler.rectifyAllCompanies();

        verify(rectificationService).rectify(docOne.getId());
        verify(rectificationService).rectify(docTwo.getId());
    }

    @Test
    void oneFailingDocumentDoesNotStopTheRest() {
        Company company = companyWith(DocumentTypeEnum.CNPJ);
        when(syncControlRepository.findAllWithCompany()).thenReturn(List.of(controlFor(company, 2, 2)));

        FiscalXmlDocument failing = new FiscalXmlDocument();
        failing.setId(UUID.randomUUID());
        FiscalXmlDocument ok = new FiscalXmlDocument();
        ok.setId(UUID.randomUUID());
        when(documentRepository.findByCompany_IdAndIssueDateBetweenOrderByIssueDateDesc(eq(company.getId()), any(), any()))
                .thenReturn(List.of(failing, ok));

        when(rectificationService.rectify(failing.getId())).thenThrow(new RuntimeException("boom"));
        when(rectificationService.rectify(ok.getId()))
                .thenReturn(new FiscalXmlRectificationResultDTO(null, null, null, null, List.of()));

        scheduler.rectifyAllCompanies();

        verify(rectificationService).rectify(ok.getId());
    }

    @Test
    void oneFailingCompanyDoesNotStopTheRest() {
        Company failingCompany = companyWith(DocumentTypeEnum.CNPJ);
        Company okCompany = companyWith(DocumentTypeEnum.CNPJ);
        when(syncControlRepository.findAllWithCompany())
                .thenReturn(List.of(controlFor(failingCompany, 1, 1), controlFor(okCompany, 1, 1)));

        when(documentRepository.findByCompany_IdAndIssueDateBetweenOrderByIssueDateDesc(eq(failingCompany.getId()), any(), any()))
                .thenThrow(new RuntimeException("boom"));
        when(documentRepository.findByCompany_IdAndIssueDateBetweenOrderByIssueDateDesc(eq(okCompany.getId()), any(), any()))
                .thenReturn(List.of());

        scheduler.rectifyAllCompanies();

        verify(documentRepository).findByCompany_IdAndIssueDateBetweenOrderByIssueDateDesc(eq(okCompany.getId()), any(), any());
    }

    private FiscalXmlSyncControl controlFor(Company company, int totalSieg, int totalImportado) {
        FiscalXmlSyncControl control = new FiscalXmlSyncControl();
        control.setCompany(company);
        control.setTotalSieg(totalSieg);
        control.setTotalImportado(totalImportado);
        return control;
    }

    private Company companyWith(DocumentTypeEnum documentType) {
        Company company = new Company(UUID.randomUUID());
        if (documentType != null) {
            company.setDocument(new Document("11594485000105", documentType));
        }
        return company;
    }
}
