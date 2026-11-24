package br.com.cshub.taxify.service;

import br.com.cshub.taxify.dto.fiscalrule.FiscalXmlRectificationResultDTO;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlRectificationProperties;
import br.com.cshub.taxify.repository.FiscalXmlDocumentItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Saving a product rule rectifies, right away, the company's notes with that product. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductFiscalRuleRectificationListenerTest {

    @Mock
    private FiscalXmlDocumentItemRepository itemRepository;
    @Mock
    private FiscalXmlRectificationService rectificationService;

    private ProductFiscalRuleRectificationListener listener(boolean enabled) {
        FiscalXmlRectificationProperties properties = new FiscalXmlRectificationProperties();
        properties.setEnabled(enabled);
        return new ProductFiscalRuleRectificationListener(itemRepository, rectificationService, properties);
    }

    @Test
    void theNotesWithTheProductFromThatSupplierAreRectified() {
        UUID company = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(itemRepository.findRectifiableDocumentIdsWithProduct(eq(company), eq("13423"), eq("15584967000135"), any()))
                .thenReturn(List.of(first, second));
        when(rectificationService.rectify(first)).thenThrow(new IllegalStateException("arquivo sumiu"));
        when(rectificationService.rectify(second)).thenReturn(mock(FiscalXmlRectificationResultDTO.class));

        listener(true).rectify(new ProductFiscalRuleSavedEvent(company, "13423", "15.584.967/0001-35"));

        // one failing note doesn't stop the others
        verify(rectificationService).rectify(first);
        verify(rectificationService).rectify(second);
    }

    @Test
    void aRuleForAnySupplierLooksAtEverySupplier() {
        UUID company = UUID.randomUUID();
        when(itemRepository.findRectifiableDocumentIdsWithProduct(eq(company), eq("25702"), isNull(), any())).thenReturn(List.of());

        listener(true).rectify(new ProductFiscalRuleSavedEvent(company, "25702", null));

        verify(itemRepository).findRectifiableDocumentIdsWithProduct(eq(company), eq("25702"), isNull(), any());
    }

    @Test
    void nothingWhenRectificationIsOff() {
        listener(false).onRuleSaved(new ProductFiscalRuleSavedEvent(UUID.randomUUID(), "13423", null));

        verifyNoInteractions(itemRepository, rectificationService);
    }

    @Test
    void nothingWithoutAProductCode() {
        listener(true).onRuleSaved(new ProductFiscalRuleSavedEvent(UUID.randomUUID(), " ", null));

        verify(rectificationService, never()).rectify(any());
    }
}
