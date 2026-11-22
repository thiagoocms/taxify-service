package br.com.cshub.taxify.domain.reform;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * The IBS/CBS reference rates of one calendar year, in percent - what the conferência
 * compares each note item's pCBS/pIBSUF/pIBSMun with. A table, not constants: the rates
 * change every year of the transition (and the reference rates are set by the Senate).
 * 2026 (test year) is seeded by V24; later years are added as they are published.
 */
@Entity
@Table(name = "ibs_cbs_rates")
public class IbsCbsRate {

    @Id
    @Column(name = "ano", nullable = false)
    private Integer ano;

    @Column(name = "aliquota_cbs", precision = 7, scale = 4, nullable = false)
    private BigDecimal aliquotaCbs;

    @Column(name = "aliquota_ibs_uf", precision = 7, scale = 4, nullable = false)
    private BigDecimal aliquotaIbsUf;

    @Column(name = "aliquota_ibs_mun", precision = 7, scale = 4, nullable = false)
    private BigDecimal aliquotaIbsMun;

    public IbsCbsRate() {
    }

    public IbsCbsRate(Integer ano, BigDecimal aliquotaCbs, BigDecimal aliquotaIbsUf, BigDecimal aliquotaIbsMun) {
        this.ano = ano;
        this.aliquotaCbs = aliquotaCbs;
        this.aliquotaIbsUf = aliquotaIbsUf;
        this.aliquotaIbsMun = aliquotaIbsMun;
    }

    public Integer getAno() {
        return ano;
    }

    public BigDecimal getAliquotaCbs() {
        return aliquotaCbs;
    }

    public BigDecimal getAliquotaIbsUf() {
        return aliquotaIbsUf;
    }

    public BigDecimal getAliquotaIbsMun() {
        return aliquotaIbsMun;
    }
}
