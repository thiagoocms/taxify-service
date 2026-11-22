package br.com.cshub.taxify.domain.xml;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.math.BigDecimal;

/**
 * The IBS/CBS group of a note item (Reforma Tributária - {@code det/imposto/IBSCBS}, NF-e
 * layout from NT 2025.002): CST, cClassTrib, the shared base and, per tax (IBS of the
 * state, IBS of the city, CBS), the rate, its reduction ({@code gRed/pRedAliq}) and the
 * value. Null when the note has no IBSCBS group (notes before 2026, or issuers not yet
 * filling it). Rates and reductions are percentages, as in the XML. {@code diferimentoOuDevolucao}
 * is true when any of the taxes has a gDif or gDevTrib group - then the value is no longer
 * base x rate and the calculation check stays out.
 */
@Embeddable
public record FiscalItemIbsCbs(
        @Column(name = "ibscbs_cst", length = 3) String cst,
        @Column(name = "ibscbs_class_trib", length = 6) String classificacaoTributaria,
        @Column(name = "ibscbs_base_calculo", precision = 19, scale = 2) BigDecimal baseCalculo,
        @Column(name = "ibs_uf_aliquota", precision = 7, scale = 4) BigDecimal aliquotaIbsUf,
        @Column(name = "ibs_uf_reducao_aliquota", precision = 7, scale = 4) BigDecimal reducaoAliquotaIbsUf,
        @Column(name = "ibs_uf_valor", precision = 19, scale = 2) BigDecimal valorIbsUf,
        @Column(name = "ibs_mun_aliquota", precision = 7, scale = 4) BigDecimal aliquotaIbsMun,
        @Column(name = "ibs_mun_reducao_aliquota", precision = 7, scale = 4) BigDecimal reducaoAliquotaIbsMun,
        @Column(name = "ibs_mun_valor", precision = 19, scale = 2) BigDecimal valorIbsMun,
        @Column(name = "ibs_valor", precision = 19, scale = 2) BigDecimal valorIbs,
        @Column(name = "cbs_aliquota", precision = 7, scale = 4) BigDecimal aliquotaCbs,
        @Column(name = "cbs_reducao_aliquota", precision = 7, scale = 4) BigDecimal reducaoAliquotaCbs,
        @Column(name = "cbs_valor", precision = 19, scale = 2) BigDecimal valorCbs,
        @Column(name = "ibscbs_diferimento_devolucao") Boolean diferimentoOuDevolucao
) {
}
