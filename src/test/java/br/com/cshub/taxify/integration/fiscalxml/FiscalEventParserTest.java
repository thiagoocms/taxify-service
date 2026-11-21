package br.com.cshub.taxify.integration.fiscalxml;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

public class FiscalEventParserTest {

    static final String KEY = "35241112345678000199550010000012341000012345";

    /** A procEventoNFe as SEFAZ/SIEG return it - shared with other tests. */
    public static String procEvento(String tpEvento, String detail, String cStat) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <procEventoNFe xmlns="http://www.portalfiscal.inf.br/nfe" versao="1.00">
                  <evento versao="1.00">
                    <infEvento Id="ID%s%s01">
                      <cOrgao>35</cOrgao><tpAmb>1</tpAmb><CNPJ>12345678000199</CNPJ>
                      <chNFe>%s</chNFe>
                      <dhEvento>2026-11-20T10:15:30-03:00</dhEvento>
                      <tpEvento>%s</tpEvento><nSeqEvento>1</nSeqEvento><verEvento>1.00</verEvento>
                      <detEvento versao="1.00">%s</detEvento>
                    </infEvento>
                  </evento>
                  <retEvento versao="1.00">
                    <infEvento><cStat>%s</cStat><nProt>135260000123456</nProt></infEvento>
                  </retEvento>
                </procEventoNFe>
                """.formatted(tpEvento, KEY, KEY, tpEvento, detail, cStat);
    }

    private final FiscalEventParser parser = new FiscalEventParser();

    @Test
    void readsARegisteredCancellation() {
        String xml = procEvento("110111", "<descEvento>Cancelamento</descEvento><nProt>1</nProt><xJust>Erro no valor</xJust>", "135");

        FiscalEventData event = parser.parse(xml.getBytes(StandardCharsets.UTF_8)).orElseThrow();

        assertThat(event.accessKey()).isEqualTo(KEY);
        assertThat(event.type()).isEqualTo("110111");
        assertThat(event.description()).isEqualTo("Cancelamento");
        assertThat(event.text()).isEqualTo("Erro no valor");
        assertThat(event.eventAt()).isEqualTo(LocalDateTime.of(2026, 11, 20, 10, 15, 30));
        assertThat(event.protocol()).isEqualTo("135260000123456");
        assertThat(event.isCancellation()).isTrue();
    }

    @Test
    void correctionLetterKeepsItsTextAndIsNotACancellation() {
        String xml = procEvento("110110", "<descEvento>Carta de Correcao</descEvento><xCorrecao>CFOP correto 5102</xCorrecao>", "135");

        FiscalEventData event = parser.parse(xml.getBytes(StandardCharsets.UTF_8)).orElseThrow();

        assertThat(event.text()).isEqualTo("CFOP correto 5102");
        assertThat(event.isCancellation()).isFalse();
    }

    @Test
    void rejectedCancellationChangesNothing() {
        String xml = procEvento("110111", "<descEvento>Cancelamento</descEvento>", "573");

        FiscalEventData event = parser.parse(xml.getBytes(StandardCharsets.UTF_8)).orElseThrow();

        assertThat(event.registered()).isFalse();
        assertThat(event.isCancellation()).isFalse();
    }

    @Test
    void aNoteIsNotAnEvent() {
        String nfe = "<nfeProc xmlns=\"http://www.portalfiscal.inf.br/nfe\"><NFe><infNFe Id=\"NFe" + KEY + "\"/></NFe></nfeProc>";

        assertThat(parser.parse(nfe.getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(parser.parse("not xml".getBytes(StandardCharsets.UTF_8))).isEmpty();
    }
}
