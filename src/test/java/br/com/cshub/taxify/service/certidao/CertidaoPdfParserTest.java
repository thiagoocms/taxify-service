package br.com.cshub.taxify.service.certidao;

import br.com.cshub.taxify.enumerated.CertidaoSituacao;
import br.com.cshub.taxify.enumerated.CertidaoTipo;
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
class CertidaoPdfParserTest {

    private final CertidaoPdfParser parser = new CertidaoPdfParser();

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
        CertidaoPdfParser.Certidao certidao = parser.parseText(NEGATIVA);

        assertThat(certidao.tipo()).isEqualTo(CertidaoTipo.CRF);
        assertThat(certidao.situacao()).isEqualTo(CertidaoSituacao.NEGATIVA);
        assertThat(certidao.numero()).isEqualTo("2026.000123456789-01");
        assertThat(certidao.emitidaEm()).isEqualTo(LocalDate.of(2026, 11, 24));
        assertThat(certidao.validaAte()).isEqualTo(LocalDate.of(2027, 2, 22));
        assertThat(certidao.cnpj()).isEqualTo("11222333000181");
    }

    @Test
    void positiveWithNegativeEffectIsNotReadAsPositiveNorNegative() {
        String text = NEGATIVA.replace("NÃO CONSTAM débitos", "CONSTAM débitos com a exigibilidade suspensa")
                .replace("Situação: NEGATIVA", "CERTIDÃO POSITIVA COM EFEITOS DE NEGATIVA");

        assertThat(parser.parseText(text).situacao()).isEqualTo(CertidaoSituacao.POSITIVA_EFEITO_NEGATIVA);
    }

    @Test
    void readsAPositive() {
        String text = NEGATIVA.replace("NÃO CONSTAM débitos", "CONSTAM os débitos abaixo")
                .replace("Situação: NEGATIVA", "Situação: POSITIVA");

        assertThat(parser.parseText(text).situacao()).isEqualTo(CertidaoSituacao.POSITIVA);
    }

    @Test
    void situationFromTheStatementWhenTheWordIsMissing() {
        String text = NEGATIVA.replace("Situação: NEGATIVA", "");

        assertThat(parser.parseText(text).situacao()).isEqualTo(CertidaoSituacao.NEGATIVA);
    }

    @Test
    void cndByTheTitle() {
        String text = NEGATIVA.replace("CERTIDÃO DE REGULARIDADE FISCAL", "CERTIDÃO NEGATIVA DE DÉBITOS FISCAIS")
                .replace("Situação: NEGATIVA", "");

        CertidaoPdfParser.Certidao certidao = parser.parseText(text);
        assertThat(certidao.tipo()).isEqualTo(CertidaoTipo.CND);
        assertThat(certidao.situacao()).isEqualTo(CertidaoSituacao.NEGATIVA);
    }

    @Test
    void withoutValidityDateIsIssuePlus90Days() {
        String text = NEGATIVA.replace("Válida até: 22/02/2027", "Validade: 90 (noventa) dias");

        assertThat(parser.parseText(text).validaAte()).isEqualTo(LocalDate.of(2026, 11, 24).plusDays(90));
        assertThat(parser.parseText(NEGATIVA.replace("Válida até: 22/02/2027", "")).validaAte())
                .isEqualTo(LocalDate.of(2026, 11, 24).plusDays(CertidaoPdfParser.VALIDITY_DAYS));
    }

    @Test
    void theCnpjIsNotTakenAsTheNumber() {
        String text = NEGATIVA.replace("Certidão Nº: 2026.000123456789-01\n", "")
                .replace("CNPJ: 11.222.333/0001-81", "inscrita no CNPJ sob o nº 11.222.333/0001-81");

        CertidaoPdfParser.Certidao certidao = parser.parseText(text);
        assertThat(certidao.numero()).isNull();
        assertThat(certidao.cnpj()).isEqualTo("11222333000181");
    }

    @Test
    void refusesWhatIsNotACertidao() {
        assertThatThrownBy(() -> parser.parseText("Extrato do contribuinte - ICMS antecipado"))
                .isInstanceOf(CertidaoPdfParser.InvalidCertidaoException.class);
        assertThatThrownBy(() -> parser.parseText("CERTIDÃO DE REGULARIDADE FISCAL Situação: NEGATIVA"))
                .isInstanceOf(CertidaoPdfParser.InvalidCertidaoException.class)
                .hasMessageContaining("validade");
        assertThatThrownBy(() -> parser.parse("not a pdf".getBytes()))
                .isInstanceOf(CertidaoPdfParser.InvalidCertidaoException.class);
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

        CertidaoPdfParser.Certidao certidao = parser.parse(out.toByteArray());

        assertThat(certidao.situacao()).isEqualTo(CertidaoSituacao.NEGATIVA);
        assertThat(certidao.validaAte()).isEqualTo(LocalDate.of(2027, 2, 22));
        assertThat(certidao.numero()).isEqualTo("2026.000123456789-01");
    }
}
