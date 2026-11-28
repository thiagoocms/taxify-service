package br.com.cshub.taxify.config;

import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentItem;
import br.com.cshub.taxify.domain.fiscaldocument.GoodsInvoice;
import br.com.cshub.taxify.domain.fiscaldocument.ItemIcms;
import br.com.cshub.taxify.support.TestNotes;
import br.com.cshub.taxify.integration.sieg.FiscalXmlFileStorage;
import br.com.cshub.taxify.integration.sieg.FiscalXmlMetadataParser;
import br.com.cshub.taxify.repository.GoodsInvoiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FiscalXmlTaxDetailBackfillTest {

    private static final String XML = """
            <nfeProc xmlns="http://www.portalfiscal.inf.br/nfe"><NFe>
              <infNFe Id="NFe35260112345678000199550010000000011000000010" versao="4.00">
                <det nItem="1"><prod><cProd>P1</cProd><CEST>0300700</CEST></prod>
                  <imposto>
                    <ICMS><ICMSSN102><orig>1</orig><CSOSN>102</CSOSN></ICMSSN102></ICMS>
                    <IBSCBS><CST>000</CST><cClassTrib>000001</cClassTrib>
                      <gIBSCBS><vBC>10.00</vBC><gCBS><pCBS>0.9000</pCBS><vCBS>0.09</vCBS></gCBS></gIBSCBS></IBSCBS>
                  </imposto></det>
                <det nItem="2"><prod><cProd>P2</cProd></prod>
                  <imposto><ICMS><ICMS00><orig>0</orig><CST>00</CST><pICMS>18</pICMS></ICMS00></ICMS>
                    <IPI><cEnq>999</cEnq><IPITrib><CST>50</CST><vBC>10.00</vBC><pIPI>10</pIPI><vIPI>1.00</vIPI></IPITrib></IPI></imposto></det>
                <total><ICMSTot><vNF>20.00</vNF></ICMSTot>
                  <IBSCBSTot><vBCIBSCBS>10.00</vBCIBSCBS><gCBS><vCBS>0.09</vCBS></gCBS></IBSCBSTot></total>
              </infNFe></NFe></nfeProc>
            """;

    private final GoodsInvoiceRepository repository = mock(GoodsInvoiceRepository.class);
    private final FiscalXmlFileStorage storage = mock(FiscalXmlFileStorage.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private FiscalXmlTaxDetailBackfill backfill;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(transactionTemplate.execute(any())).thenAnswer(inv -> ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(null));
        backfill = new FiscalXmlTaxDetailBackfill(true, repository, storage, new FiscalXmlMetadataParser(), transactionTemplate);
    }

    @Test
    void fillsOnlyTheNewFieldsOfTheExistingItemsAndMarksTheNote() throws IOException {
        GoodsInvoice document = document("s3://bucket/a.xml");
        FiscalDocumentItem second = item(document, 2, "P2");
        second.setIcms(new ItemIcms(null, null, null, null, null, new java.math.BigDecimal("12"), null, null, null));
        FiscalDocumentItem first = item(document, 1, "P1");
        when(repository.findById(document.getId())).thenReturn(Optional.of(document));
        when(storage.read("s3://bucket/a.xml")).thenReturn(XML.getBytes(StandardCharsets.UTF_8));

        assertThat(backfill.backfill(document.getId())).isEqualTo(FiscalXmlTaxDetailBackfill.Outcome.UPDATED);

        assertThat(first.getIcms().csosn()).isEqualTo("102");
        assertThat(first.getIcms().origin()).isEqualTo("1");
        assertThat(first.getProduct().cest()).isEqualTo("0300700");
        assertThat(first.getIbsCbs().cbsAmount()).isEqualByComparingTo("0.09");
        assertThat(second.getIcms().origin()).isEqualTo("0");
        assertThat(second.getIbsCbs()).isNull();
        // IPI CST/value, always empty before parser version 3, are rewritten
        assertThat(second.getIpi().cst()).isEqualTo("50");
        assertThat(second.getIpi().amount()).isEqualByComparingTo("1.00");
        // fields the old parser already had are not rewritten
        assertThat(second.getIcms().rate()).isEqualByComparingTo("12");
        assertThat(document.getIbsCbsTotals().cbsAmount()).isEqualByComparingTo("0.09");
        assertThat(document.getMetadataVersion()).isEqualTo(FiscalXmlMetadataParser.CURRENT_VERSION);
        verify(repository).save(document);
    }

    @Test
    void purgedFileIsMarkedDoneAndStorageFailureIsLeftForNextRun() throws IOException {
        GoodsInvoice purged = document("s3://bucket/purged.xml");
        GoodsInvoice unreachable = document("s3://bucket/down.xml");
        when(repository.findById(purged.getId())).thenReturn(Optional.of(purged));
        when(repository.findById(unreachable.getId())).thenReturn(Optional.of(unreachable));
        when(storage.read("s3://bucket/purged.xml")).thenThrow(new NoSuchFileException("purged"));
        when(storage.read("s3://bucket/down.xml")).thenThrow(new IOException("S3 down"));

        assertThat(backfill.backfill(purged.getId())).isEqualTo(FiscalXmlTaxDetailBackfill.Outcome.MISSING);
        assertThat(backfill.backfill(unreachable.getId())).isEqualTo(FiscalXmlTaxDetailBackfill.Outcome.FAILED);

        assertThat(purged.getMetadataVersion()).isEqualTo(FiscalXmlMetadataParser.CURRENT_VERSION);
        assertThat(unreachable.getMetadataVersion()).isNull();
        verify(repository, never()).save(unreachable);
    }

    @Test
    void pagesThroughTheNotesById() throws IOException {
        GoodsInvoice document = document("s3://bucket/a.xml");
        item(document, 1, "P1");
        when(repository.findIdsParsedBefore(anyInt(), eq(""), any())).thenReturn(List.of(document.getId()));
        when(repository.findIdsParsedBefore(anyInt(), eq(document.getId().toString()), any())).thenReturn(List.of());
        when(repository.findById(document.getId())).thenReturn(Optional.of(document));
        when(storage.read("s3://bucket/a.xml")).thenReturn(XML.getBytes(StandardCharsets.UTF_8));

        backfill.backfillAll();

        assertThat(document.getMetadataVersion()).isEqualTo(FiscalXmlMetadataParser.CURRENT_VERSION);
    }

    private static GoodsInvoice document(String path) {
        return TestNotes.note(TestNotes.company(TestNotes.COMPANY_CNPJ)).xml(path).metadataVersion(null).build();
    }

    private static FiscalDocumentItem item(GoodsInvoice document, int number, String code) {
        FiscalDocumentItem item = TestNotes.item(number).code(code).build();
        document.addItem(item);
        return item;
    }
}
