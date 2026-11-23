package br.com.cshub.taxify.common;

import br.com.cshub.taxify.domain.fiscalrule.ProductFiscalRule;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
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
        assertThat(IssuePeriod.end(day)).isAfter(LocalDateTime.of(2026, 11, 23, 23, 59, 59));
        assertThat(IssuePeriod.start(null)).isEqualTo(LocalDateTime.MIN);
        assertThat(IssuePeriod.end(null)).isEqualTo(LocalDateTime.MAX);
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
    void rulesByProductSkipRulesWithoutCodeAndKeepTheFirst() {
        ProductFiscalRule first = rule("P1");
        ProductFiscalRule duplicate = rule("P1");

        assertThat(ProductFiscalRule.byProduct(List.of(first, duplicate, rule(null))))
                .containsOnlyKeys("P1")
                .containsEntry("P1", first);
    }

    @Test
    void noteKeyIsTheAccessKeyOrTheId() {
        FiscalXmlDocument document = new FiscalXmlDocument();
        document.setId(UUID.randomUUID());

        assertThat(document.accessKeyOrId()).isEqualTo(document.getId().toString());
        document.setAccessKey(" ");
        assertThat(document.accessKeyOrId()).isEqualTo(document.getId().toString());
        document.setAccessKey("35240112345678000199550010000000011000000010");
        assertThat(document.accessKeyOrId()).isEqualTo("35240112345678000199550010000000011000000010");
    }

    private static ProductFiscalRule rule(String codigo) {
        ProductFiscalRule rule = new ProductFiscalRule(UUID.randomUUID());
        rule.setCodigoProduto(codigo);
        return rule;
    }
}
