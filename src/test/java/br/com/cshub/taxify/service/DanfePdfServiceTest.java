package br.com.cshub.taxify.service;

import br.com.cshub.taxify.enumerated.FiscalDocumentStatus;
import br.com.cshub.taxify.enumerated.FiscalDirection;
import br.com.cshub.taxify.common.DocumentNumbers;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlDestinatarioDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlDetailDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlEmitenteDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlEnderecoDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlIdentificacaoDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlImpostoItemDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlProdutoDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlTotaisDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DanfePdfServiceTest {

    private static final String KEY = "35241112345678000199550010000012341000012345";

    private static FiscalXmlDetailDTO detail(String ambiente) {
        FiscalXmlEnderecoDTO end = new FiscalXmlEnderecoDTO("Rua das Flores", "100", "Sala 2", "Centro", "3550308",
                "São Paulo", "SP", "01001000", "1058", "BRASIL", "1133334444");
        return new FiscalXmlDetailDTO(UUID.randomUUID(), UUID.randomUUID(), KEY, "NFE", "12345678000199", "98765432000188",
                LocalDateTime.of(2026, 11, 10, 14, 30), false, 2,
                new FiscalXmlIdentificacaoDTO("35", "VENDA DE MERCADORIA", "55", "1", "1234",
                        LocalDateTime.of(2026, 11, 10, 14, 30), null, "1", "1", "3550308", "1", "1", "5", ambiente,
                        "1", "0", "1", "0", "1.0"),
                new FiscalXmlEmitenteDTO("12345678000199", null, "Empresa Emitente Ltda", "Emitente", "123456789",
                        null, null, null, "1", end),
                new FiscalXmlDestinatarioDTO("98765432000188", null, null, "Cliente Destinatário S/A", "987654321", "1",
                        null, end),
                new FiscalXmlTotaisDTO(new BigDecimal("1000"), new BigDecimal("180"), null, null, null, null, null,
                        new BigDecimal("1000"), null, null, null, null, null, null, null, null,
                        new BigDecimal("1000"), new BigDecimal("300.50")),
                FiscalDirection.OUTBOUND, true, FiscalDocumentStatus.AUTHORIZED, null);
    }

    private static List<FiscalXmlProdutoDTO> produtos(int n) {
        return IntStream.rangeClosed(1, n).mapToObj(i -> new FiscalXmlProdutoDTO(i, "P" + i, null,
                "Produto de teste número " + i, "84713012", "5102", "UN", new BigDecimal("2"),
                new BigDecimal("250"), new BigDecimal("500"), "UN", new BigDecimal("2"), new BigDecimal("250"),
                new FiscalXmlImpostoItemDTO("00", new BigDecimal("500"), new BigDecimal("18"), new BigDecimal("90"),
                        null, null, null, null, null, null))).toList();
    }

    @Test
    void generatesPdfNamedAfterTheAccessKey() throws Exception {
        FiscalXmlQueryService queryService = mock(FiscalXmlQueryService.class);
        FiscalXmlDetailDTO detail = detail("1");
        when(queryService.findDetail(detail.id())).thenReturn(detail);
        when(queryService.findProdutos(detail.id())).thenReturn(produtos(60));

        DanfePdfService.DanfePdf pdf = new DanfePdfService(queryService).generate(detail.id());

        assertThat(pdf.fileName()).isEqualTo(KEY + ".pdf");
        assertThat(new String(pdf.content(), 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        String out = System.getProperty("danfe.out");
        if (out != null) {
            Files.write(Path.of(out), pdf.content());
        }
    }

    @Test
    void rendersHomologacaoAndMissingSectionsWithoutFailing() {
        FiscalXmlDetailDTO base = detail("2");
        FiscalXmlDetailDTO sparse = new FiscalXmlDetailDTO(base.id(), base.companyId(), null, "NFE", null, null, null,
                true, 0, null, null, null, null, null, false, null, null);
        DanfePdfService service = new DanfePdfService(mock(FiscalXmlQueryService.class));

        assertThat(service.render(base, List.of())).isNotEmpty();
        assertThat(service.render(sparse, List.of())).isNotEmpty();
    }

    @Test
    void formatsKeyDocumentAndMoney() {
        assertThat(DanfePdfService.formatAccessKey("12345678")).isEqualTo("1234 5678");
        assertThat(DocumentNumbers.format("12345678000199")).isEqualTo("12.345.678/0001-99");
        assertThat(DocumentNumbers.format("12345678901")).isEqualTo("123.456.789-01");
        assertThat(DanfePdfService.formatMoney(new BigDecimal("1234.5"))).isEqualTo("1.234,50");
    }
}
