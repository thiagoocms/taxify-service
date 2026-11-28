package legacy.xml;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.math.BigDecimal;

/**
 * The note's IBS/CBS totals ({@code total/IBSCBSTot}) - what the sum of the items'
 * {@link FiscalItemIbsCbs} has to match. Null when the note has no IBSCBSTot group.
 */
@Embeddable
public record FiscalIbsCbsTotals(
        @Column(name = "total_ibscbs_base_calculo", precision = 19, scale = 2) BigDecimal baseCalculo,
        @Column(name = "total_valor_ibs_uf", precision = 19, scale = 2) BigDecimal valorIbsUf,
        @Column(name = "total_valor_ibs_mun", precision = 19, scale = 2) BigDecimal valorIbsMun,
        @Column(name = "total_valor_ibs", precision = 19, scale = 2) BigDecimal valorIbs,
        @Column(name = "total_valor_cbs", precision = 19, scale = 2) BigDecimal valorCbs
) {
}
