package br.com.cshub.taxify.service.fronteira;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The e-Fisco extract as PDFBox reads it (sorted by position) - fictitious company and suppliers. */
class EfiscoExtractParserTest {

    private static final String TEXT = """
            EXTRATO DE NOTAS FISCAIS RELATIVAS A OPERAÇÕES INTERESTADUAIS SUJEITAS
            AO ICMS ANTECIPADO
            Parâmetros Utilizados:
            Período Fiscal: 11/2026
            Inscrição Estadual: 0123456-78
            Dados Complementares:
            Razão Social: CONFECCOES EXEMPLO LTDA
            ARE: 704 - ARE SANTA CRUZ DO CAPIBARIBE
            Código da Receita: 00058-2
            Credenciamento Antecipação: SIM
            Credenciamento Interno: TECIDOS E ARMARINHO (INDUSTRIA)
            Data de Vencimento: 28/12/2026
            Data de Emissão:
            Nº do Extrato: 10000000-1
            REGISTRO DE NOTA DATA PASSAGEM NOTA FISCAL UF VALOR DA NF ICMS DEVIDO CNPJ EMITENTE
            012.6.500000001-1 018049 SP 887,00 48,78 11.111.111/0001-11
            012.6.500000002-2 05/11/2026 473 RN 13.720,00 754,60 22.222.222/0001-22
            012.6.500000003-3 1810 MG 4.261,61 750,04 33.333.333/0001-33
            Página 1
            Emitido em: 24/11/2026 01:23:41
            EXTRATO DE NOTAS FISCAIS RELATIVAS A OPERAÇÕES INTERESTADUAIS SUJEITAS
            AO ICMS ANTECIPADO
            012.6.500000004-4 487 RN 2.400,00 132,00 22.222.222/0001-22
            ICMS PAGO ICMS REG. DÉB ICMS EM PROCESSO
            100,00 0,00 0,00
            ICMS AUTUADO ICMS SOB MANDADO DE SEGURANÇA ICMS ANISTIADO ICMS A RECOLHER
            0,00 0,00 0,00 1.585,42
            PAGUE O ICMS EM DIA, SEU ATRASO IMPLICARÁ NO DESCREDENCIAMENTO AUTOMÁTICO.
            Página 2
            """;

    private final EfiscoExtractParser parser = new EfiscoExtractParser();

    @Test
    void headerNotesAcrossPagesAndFooterTotals() {
        EfiscoExtractParser.Extract extract = parser.parseText(TEXT);

        assertThat(extract.period()).isEqualTo(YearMonth.of(2026, 11));
        assertThat(extract.stateRegistration()).isEqualTo("0123456-78");
        assertThat(extract.legalName()).isEqualTo("CONFECCOES EXEMPLO LTDA");
        assertThat(extract.credenciamentoAntecipacao()).isEqualTo("SIM");
        assertThat(extract.credenciamentoInterno()).isEqualTo("TECIDOS E ARMARINHO (INDUSTRIA)");
        assertThat(extract.revenueCode()).isEqualTo("00058-2");
        assertThat(extract.dueDate()).isEqualTo(LocalDate.of(2026, 12, 28));
        assertThat(extract.extractNumber()).isEqualTo("10000000-1");

        assertThat(extract.notes()).hasSize(4);
        EfiscoExtractParser.Note first = extract.notes().get(0);
        assertThat(first.invoiceNumber()).isEqualTo("18049"); // leading zero dropped, as nNF is stored
        assertThat(first.uf()).isEqualTo("SP");
        assertThat(first.invoiceAmount()).isEqualByComparingTo("887.00");
        assertThat(first.icmsDue()).isEqualByComparingTo("48.78");
        assertThat(first.issuerCnpj()).isEqualTo("11111111000111");
        assertThat(first.crossingDate()).isNull();
        assertThat(extract.notes().get(1).crossingDate()).isEqualTo("05/11/2026");
        assertThat(extract.notes().get(1).invoiceAmount()).isEqualByComparingTo("13720.00");
        assertThat(extract.notes().get(3).line()).isEqualTo(4);

        assertThat(extract.total(EfiscoExtractParser.A_RECOLHER)).isEqualByComparingTo("1585.42");
        assertThat(extract.total(EfiscoExtractParser.PAGO)).isEqualByComparingTo("100.00");
        assertThat(extract.total(EfiscoExtractParser.MANDADO_SEGURANCA)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(extract.totals()).containsKey(EfiscoExtractParser.REG_DEBITO);
    }

    /** The PDF's accents sometimes come out broken - the labels still match. */
    @Test
    void brokenAccentsInTheLabels() {
        String broken = TEXT.replace("Período", "Per�odo").replace("Inscrição", "Inscri��o")
                .replace("SEGURANÇA", "SEGURAN�A").replace("Nº do", "N� do");

        EfiscoExtractParser.Extract extract = parser.parseText(broken);

        assertThat(extract.period()).isEqualTo(YearMonth.of(2026, 11));
        assertThat(extract.stateRegistration()).isEqualTo("0123456-78");
        assertThat(extract.extractNumber()).isEqualTo("10000000-1");
        assertThat(extract.totals()).containsKey(EfiscoExtractParser.MANDADO_SEGURANCA);
    }

    @Test
    void anotherDocumentIsRefused() {
        assertThatThrownBy(() -> parser.parseText("DANFE\nNota fiscal eletrônica\n"))
                .isInstanceOf(EfiscoExtractParser.InvalidExtractException.class);
        assertThatThrownBy(() -> parser.parseText("EXTRATO ... AO ICMS ANTECIPADO\nPeríodo Fiscal: 11/2026\n"))
                .isInstanceOf(EfiscoExtractParser.InvalidExtractException.class)
                .hasMessageContaining("Nenhuma nota");
        assertThatThrownBy(() -> parser.parse(new byte[]{1, 2, 3}))
                .isInstanceOf(EfiscoExtractParser.InvalidExtractException.class);
    }

    /** A real extract, read from the PDF - only where one is at hand (EFISCO_SAMPLE_PDF); never committed. */
    @Test
    @EnabledIfEnvironmentVariable(named = "EFISCO_SAMPLE_PDF", matches = ".+")
    void aRealExtract() throws Exception {
        EfiscoExtractParser.Extract extract = parser.parse(Files.readAllBytes(Path.of(System.getenv("EFISCO_SAMPLE_PDF"))));

        BigDecimal sum = extract.notes().stream().map(EfiscoExtractParser.Note::icmsDue).reduce(BigDecimal.ZERO, BigDecimal::add);
        System.out.printf("periodo=%s IE=%s notas=%d soma=%s a recolher=%s totais=%s%n", extract.period(),
                extract.stateRegistration(), extract.notes().size(), sum, extract.total(EfiscoExtractParser.A_RECOLHER), extract.totals());
        assertThat(extract.notes()).isNotEmpty();
        assertThat(extract.period()).isNotNull();
        assertThat(sum.add(extract.total(EfiscoExtractParser.PAGO)))
                .isGreaterThanOrEqualTo(extract.total(EfiscoExtractParser.A_RECOLHER));
    }
}
