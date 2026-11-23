package br.com.cshub.taxify.dto.fiscalxml;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** The service invoices (NFS-e) screen. */
public final class ServiceInvoiceDTOs {

    private ServiceInvoiceDTOs() {
    }

    /**
     * @param direction  PRESTADA, TOMADA or empty for both
     * @param participant piece of the other party's name or digits of its CNPJ/CPF
     */
    public record Filter(@NotBlank String companyId, LocalDate dataEmissaoInicio, LocalDate dataEmissaoFim,
                         String direction, String participant, String numero, Boolean somenteComRetencao,
                         Integer page, Integer size,
                         /** SO_SIEG / SO_ADN: in one source and not the other for 48h+; blank = no comparison. */
                         String fonte) {

        public static final String ONLY_SIEG = "SO_SIEG";
        public static final String ONLY_ADN = "SO_ADN";

        public Filter(String companyId, LocalDate dataEmissaoInicio, LocalDate dataEmissaoFim, String direction,
                      String participant, String numero, Boolean somenteComRetencao, Integer page, Integer size) {
            this(companyId, dataEmissaoInicio, dataEmissaoFim, direction, participant, numero, somenteComRetencao, page, size, null);
        }
    }

    /** @param totalRetido ISS retained + IRRF + CSLL + INSS + PIS/COFINS when retained */
    public record Summary(UUID id, String numero, String layout, LocalDateTime issueDate, LocalDate competence,
                          String direction, String prestadorNome, String prestadorDocumento, String tomadorNome,
                          String tomadorDocumento, String descricaoServico, BigDecimal valorServico, BigDecimal valorIss,
                          boolean issRetido, BigDecimal totalRetido, boolean canceled, String origem) {
    }

    public record Detail(UUID id, UUID companyId, String accessKey, String layout, String numero, String codigoVerificacao,
                         LocalDateTime issueDate, LocalDate competence, String direction,
                         String prestadorDocumento, String prestadorNome, String prestadorMunicipio,
                         String tomadorDocumento, String tomadorNome, String municipioIncidencia,
                         String codigoServico, String codigoTributacaoMunicipal, String descricaoServico,
                         BigDecimal valorServico, BigDecimal valorDeducoes, BigDecimal baseCalculo, BigDecimal aliquotaIss,
                         BigDecimal valorIss, boolean issRetido, BigDecimal valorIssRetido, BigDecimal valorIrrf,
                         BigDecimal valorPis, BigDecimal valorCofins, boolean pisCofinsRetidos, BigDecimal valorCsll,
                         BigDecimal valorInss, BigDecimal valorLiquido, BigDecimal totalRetido, boolean canceled,
                         String origem) {
    }

    /** One direction of the period, canceled notes left out. */
    public record DirectionTotals(long count, BigDecimal valorServico, BigDecimal valorIss, BigDecimal issRetido,
                                  BigDecimal federaisRetidos, BigDecimal totalRetido) {
    }

    /** @param soSieg / soAdn notes in one source and not the other for 48h+ (the SIEG x Ambiente Nacional comparison) */
    public record Totals(DirectionTotals prestadas, DirectionTotals tomadas, long soSieg, long soAdn) {
    }
}
