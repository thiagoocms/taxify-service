package br.com.cshub.taxify.service.taxclearance;

import br.com.cshub.taxify.enumerated.TaxClearanceStatus;
import br.com.cshub.taxify.enumerated.TaxClearanceType;
import com.lowagie.text.Document;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The e-Fisco certidão could not be issued for real (the public page asks for a captcha), so these texts follow the
 * layout of the SEFAZ-PE certidão: header, number, company (CNPJ/IE), the statement, situation, issue and validity.
 */
class TaxClearancePdfParserTest {

    private final TaxClearancePdfParser parser = new TaxClearancePdfParser();

    private static final String NEGATIVA = """
            GOVERNO DO ESTADO DE PERNAMBUCO
            SECRETARIA DA FAZENDA
            CERTIDÃO DE REGULARIDADE FISCAL
            Certidão Nº: 2026.000123456789-01
            Razão Social: EMPRESA EXEMPLO LTDA
            CNPJ: 11.222.333/0001-81      Inscrição Estadual: 1234567-89
            Certificamos que, até a presente data, NÃO CONSTAM débitos de tributos estaduais em nome do contribuinte acima.
            Situação: NEGATIVA
            Data de Emissão: 24/11/2026      Válida até: 22/02/2027
            A autenticidade desta certidão pode ser confirmada no e-Fisco.
            """;

    @Test
    void readsANegativeCrf() {
        TaxClearancePdfParser.TaxClearance clearance = parser.parseText(NEGATIVA);

        assertThat(clearance.type()).isEqualTo(TaxClearanceType.CRF);
        assertThat(clearance.status()).isEqualTo(TaxClearanceStatus.NEGATIVA);
        assertThat(clearance.number()).isEqualTo("2026.000123456789-01");
        assertThat(clearance.issuedOn()).isEqualTo(LocalDate.of(2026, 11, 24));
        assertThat(clearance.validUntil()).isEqualTo(LocalDate.of(2027, 2, 22));
        assertThat(clearance.cnpj()).isEqualTo("11222333000181");
    }

    @Test
    void positiveWithNegativeEffectIsNotReadAsPositiveNorNegative() {
        String text = NEGATIVA.replace("NÃO CONSTAM débitos", "CONSTAM débitos com a exigibilidade suspensa")
                .replace("Situação: NEGATIVA", "CERTIDÃO POSITIVA COM EFEITOS DE NEGATIVA");

        assertThat(parser.parseText(text).status()).isEqualTo(TaxClearanceStatus.POSITIVA_EFEITO_NEGATIVA);
    }

    @Test
    void readsAPositive() {
        String text = NEGATIVA.replace("NÃO CONSTAM débitos", "CONSTAM os débitos abaixo")
                .replace("Situação: NEGATIVA", "Situação: POSITIVA");

        assertThat(parser.parseText(text).status()).isEqualTo(TaxClearanceStatus.POSITIVA);
    }

    @Test
    void situationFromTheStatementWhenTheWordIsMissing() {
        String text = NEGATIVA.replace("Situação: NEGATIVA", "");

        assertThat(parser.parseText(text).status()).isEqualTo(TaxClearanceStatus.NEGATIVA);
    }

    @Test
    void cndByTheTitle() {
        String text = NEGATIVA.replace("CERTIDÃO DE REGULARIDADE FISCAL", "CERTIDÃO NEGATIVA DE DÉBITOS FISCAIS")
                .replace("Situação: NEGATIVA", "");

        TaxClearancePdfParser.TaxClearance clearance = parser.parseText(text);
        assertThat(clearance.type()).isEqualTo(TaxClearanceType.CND);
        assertThat(clearance.status()).isEqualTo(TaxClearanceStatus.NEGATIVA);
    }

    @Test
    void withoutValidityDateIsIssuePlus90Days() {
        String text = NEGATIVA.replace("Válida até: 22/02/2027", "Validade: 90 (noventa) dias");

        assertThat(parser.parseText(text).validUntil()).isEqualTo(LocalDate.of(2026, 11, 24).plusDays(90));
        assertThat(parser.parseText(NEGATIVA.replace("Válida até: 22/02/2027", "")).validUntil())
                .isEqualTo(LocalDate.of(2026, 11, 24).plusDays(TaxClearancePdfParser.VALIDITY_DAYS));
    }

    @Test
    void theCnpjIsNotTakenAsTheNumber() {
        String text = NEGATIVA.replace("Certidão Nº: 2026.000123456789-01\n", "")
                .replace("CNPJ: 11.222.333/0001-81", "inscrita no CNPJ sob o nº 11.222.333/0001-81");

        TaxClearancePdfParser.TaxClearance clearance = parser.parseText(text);
        assertThat(clearance.number()).isNull();
        assertThat(clearance.cnpj()).isEqualTo("11222333000181");
    }

    @Test
    void refusesWhatIsNotATaxClearance() {
        assertThatThrownBy(() -> parser.parseText("Extrato do contribuinte - ICMS antecipado"))
                .isInstanceOf(TaxClearancePdfParser.InvalidTaxClearanceException.class);
        assertThatThrownBy(() -> parser.parseText("CERTIDÃO DE REGULARIDADE FISCAL Situação: NEGATIVA"))
                .isInstanceOf(TaxClearancePdfParser.InvalidTaxClearanceException.class)
                .hasMessageContaining("validade");
        assertThatThrownBy(() -> parser.parse("not a pdf".getBytes()))
                .isInstanceOf(TaxClearancePdfParser.InvalidTaxClearanceException.class);
    }

    @Test
    void readsTheTextOfARealPdf() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document();
        PdfWriter.getInstance(document, out);
        document.open();
        for (String line : NEGATIVA.split("\n")) {
            document.add(new Paragraph(line));
        }
        document.close();

        TaxClearancePdfParser.TaxClearance clearance = parser.parse(out.toByteArray());

        assertThat(clearance.status()).isEqualTo(TaxClearanceStatus.NEGATIVA);
        assertThat(clearance.validUntil()).isEqualTo(LocalDate.of(2027, 2, 22));
        assertThat(clearance.number()).isEqualTo("2026.000123456789-01");
    }
}
