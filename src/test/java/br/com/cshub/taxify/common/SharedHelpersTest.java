package br.com.cshub.taxify.common;

import br.com.cshub.taxify.domain.fiscalrule.ProductFiscalRule;
import br.com.cshub.taxify.domain.fiscalrule.ProductRules;
import br.com.cshub.taxify.service.fiscalcheck.OperationCfops;
import br.com.cshub.taxify.domain.fiscaldocument.GoodsInvoice;
import br.com.cshub.taxify.support.TestNotes;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlDom;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlDomParser;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The helpers shared across services: ids from text, issue period, XML lookup, rules by product, note key. */
class SharedHelpersTest {

    @Test
    void invalidOrMissingIdIsABadRequest() {
        MessageService messages = mock(MessageService.class);
        when(messages.getMessage("error.generic.bad.request")).thenReturn("bad request");
        UUID id = UUID.randomUUID();

        assertThat(RequestIds.parse(id.toString(), messages)).isEqualTo(id);
        assertThatThrownBy(() -> RequestIds.parse("x", messages)).isInstanceOf(BadRequestException.class).hasMessage("bad request");
        assertThatThrownBy(() -> RequestIds.parse(null, messages)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void issuePeriodCoversWholeDaysAndIsOpenWithoutDates() {
        LocalDate day = LocalDate.of(2026, 11, 23);

        assertThat(IssuePeriod.start(day)).isEqualTo(LocalDateTime.of(2026, 11, 23, 0, 0));
        // half-open: the next day's 00:00 is the exclusive end (never a last instant the driver rounds up)
        assertThat(IssuePeriod.endExclusive(day)).isEqualTo(LocalDateTime.of(2026, 11, 24, 0, 0));
        assertThat(IssuePeriod.start((LocalDate) null)).isEqualTo(IssuePeriod.OPEN_START);
        assertThat(IssuePeriod.endExclusive((LocalDate) null)).isEqualTo(IssuePeriod.OPEN_END);
        assertThat(IssuePeriod.monthEndExclusive(java.time.YearMonth.of(2026, 11))).isEqualTo(LocalDateTime.of(2026, 12, 1, 0, 0));
        assertThat(IssuePeriod.siegStart(day)).isEqualTo(LocalDateTime.of(2026, 11, 22, 21, 0));
        assertThat(IssuePeriod.siegEndExclusive(day)).isEqualTo(LocalDateTime.of(2026, 11, 23, 21, 0));
    }

    @Test
    void xmlLookupIgnoresTheNamespaceAndToleratesMissingElements() throws Exception {
        Document dom = FiscalXmlDomParser.parse("""
                <nfeProc xmlns="http://www.portalfiscal.inf.br/nfe"><infNFe><ide><nNF> 12 </nNF></ide></infNFe></nfeProc>
                """.getBytes(StandardCharsets.UTF_8));

        assertThat(FiscalXmlDom.text(FiscalXmlDom.first(dom, "ide"), "nNF")).isEqualTo(" 12 ");
        assertThat(FiscalXmlDom.first(dom, "emit")).isNull();
        assertThat(FiscalXmlDom.text(null, "nNF")).isNull();
    }

    @Test
    void theSupplierRuleWinsOverTheGeneralOneOfTheCode() {
        ProductFiscalRule general = rule("P1");
        ProductFiscalRule ofSupplier = rule("P1");
        ofSupplier.setSupplierDocument("11222333000181");
        ProductRules rules = ProductRules.of(List.of(general, ofSupplier, rule(null)));

        assertThat(rules.find("P1", "11.222.333/0001-81")).contains(ofSupplier);
        assertThat(rules.find("P1", "99888777000166")).contains(general);
        assertThat(rules.find("P1", null)).contains(general);
        assertThat(rules.find("P2", "11222333000181")).isEmpty();
        assertThat(ProductRules.of(List.of(ofSupplier)).find("P1", "99888777000166")).isEmpty();
    }

    @Test
    void theCfopFollowsTheOperationOfTheNote() {
        assertThat(OperationCfops.forOperation("1102", "1")).isEqualTo("1102");
        assertThat(OperationCfops.forOperation("1102", "2")).isEqualTo("2102");
        assertThat(OperationCfops.forOperation("2102", "1")).isEqualTo("1102");
        assertThat(OperationCfops.forOperation("1102", "3")).isEqualTo("3102");
        assertThat(OperationCfops.forOperation("5102", "2")).isEqualTo("6102"); // an exit rule stays an exit
        assertThat(OperationCfops.forOperation("1102", null)).isEqualTo("1102");
        assertThat(OperationCfops.forOperation("1102", "9")).isEqualTo("1102");
        assertThat(OperationCfops.forOperation(" 1556 ", "2")).isEqualTo("2556");
    }

    @Test
    void noteKeyIsTheAccessKeyOrTheId() {
        GoodsInvoice document = TestNotes.note(TestNotes.company(TestNotes.COMPANY_CNPJ)).withoutKey().build();

        assertThat(document.accessKeyOrId()).isEqualTo(document.getId().toString());
        document.setAccessKey(" ");
        assertThat(document.accessKeyOrId()).isEqualTo(document.getId().toString());
        document.setAccessKey("35240112345678000199550010000000011000000010");
        assertThat(document.accessKeyOrId()).isEqualTo("35240112345678000199550010000000011000000010");
    }

    private static ProductFiscalRule rule(String codigo) {
        ProductFiscalRule rule = new ProductFiscalRule(UUID.randomUUID());
        rule.setProductCode(codigo);
        return rule;
    }
}
