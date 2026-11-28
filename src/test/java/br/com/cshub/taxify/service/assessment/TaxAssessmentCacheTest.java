package br.com.cshub.taxify.service.assessment;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentItem;
import br.com.cshub.taxify.domain.fiscaldocument.GoodsInvoice;
import br.com.cshub.taxify.domain.document.Document;
import br.com.cshub.taxify.enumerated.DocumentTypeEnum;
import br.com.cshub.taxify.support.TestNotes;
import br.com.cshub.taxify.dto.assessment.TaxAssessmentDTO;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TaxAssessmentCacheTest {

    private final AtomicLong now = new AtomicLong();
    private final TaxAssessmentCache cache = new TaxAssessmentCache(now::get);
    private final YearMonth october = YearMonth.of(2026, 10);

    private static TaxAssessmentDTO dto(UUID companyId) {
        return new TaxAssessmentDTO(companyId.toString(), "Empresa", null, "2026-10", null, null, null, null, null, 0,
                null, false, null, null, null, null, null, null, null);
    }

    @Test
    void keepsTheMonthUntilTheTtl() {
        UUID id = UUID.randomUUID();
        cache.put(id, october, dto(id), cache.version(id));

        assertThat(cache.get(id, october)).isPresent();
        assertThat(cache.get(id, october.minusMonths(1))).isEmpty();

        now.addAndGet(TaxAssessmentCache.TTL_NANOS + 1);
        assertThat(cache.get(id, october)).isEmpty();
    }

    @Test
    void aChangeDropsOnlyThatCompany() {
        UUID changed = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        cache.put(changed, october, dto(changed), cache.version(changed));
        cache.put(other, october, dto(other), cache.version(other));

        cache.evictCompany(changed);

        assertThat(cache.get(changed, october)).isEmpty();
        assertThat(cache.get(other, october)).isPresent();
    }

    @Test
    void aCalculationThatStartedBeforeAChangeIsNotKept() {
        UUID id = UUID.randomUUID();
        long before = cache.version(id);
        cache.evictCompany(id); // a note arrived while the month was being calculated

        cache.put(id, october, dto(id), before);

        assertThat(cache.get(id, october)).isEmpty();
    }

    @Test
    void writesOfNotesItemsAndTheCompanyDropItsEntries() {
        TaxAssessmentCacheInvalidator invalidator = new TaxAssessmentCacheInvalidator(mock(EntityManagerFactory.class), cache);
        Company company = new Company();
        UUID id = UUID.randomUUID();
        company.setId(id);
        company.setDocument(new Document(TestNotes.COMPANY_CNPJ, DocumentTypeEnum.CNPJ));
        GoodsInvoice document = TestNotes.note(company).build();
        FiscalDocumentItem item = TestNotes.item(1).build();
        document.addItem(item);

        cache.put(id, october, dto(id), cache.version(id));
        invalidator.changed(item);
        assertThat(cache.get(id, october)).isEmpty();

        cache.put(id, october, dto(id), cache.version(id));
        invalidator.changed(document);
        assertThat(cache.get(id, october)).isEmpty();

        cache.put(id, october, dto(id), cache.version(id));
        invalidator.changed("something else");
        assertThat(cache.get(id, october)).isPresent();
    }
}
