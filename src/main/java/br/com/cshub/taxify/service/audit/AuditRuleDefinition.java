package br.com.cshub.taxify.service.audit;

import br.com.cshub.taxify.enumerated.PendencySeverity;

import java.time.LocalDate;

/**
 * The Auditoria tipo malha rules (roadmap M9) as the code knows them. Each one becomes a row of
 * audit_rules (created on first use), where the fiscal team marks it as checked, turns it off or
 * dates it; the code only supplies the defaults. Findings are indications to check, never an
 * infringement stated.
 */
public enum AuditRuleDefinition {

    NUMERACAO_LACUNA(Group.INTEGRIDADE, PendencySeverity.MEDIA, "Lacuna na numeração das notas emitidas",
            "Ajuste SINIEF 07/2005, cláusula décima quarta (numeração sequencial e inutilização de números não usados)",
            null, null),
    NUMERO_DUPLICADO(Group.INTEGRIDADE, PendencySeverity.ALTA, "Mesmo número de nota com duas chaves",
            "Ajuste SINIEF 07/2005, cláusula terceira (numeração sequencial única por série)", null, null),
    DEVOLUCAO_SEM_ORIGINAL(Group.INTEGRIDADE, PendencySeverity.MEDIA, "Devolução sem a nota original",
            "Ajuste SINIEF 07/2005 e MOC da NF-e (finNFe 4 exige a nota referenciada - NFref)", null, null),
    NOTA_SO_RESUMO(Group.INTEGRIDADE, PendencySeverity.BAIXA, "Entrada só com o resumo da SEFAZ",
            "NT 2014.002 (Distribuição DF-e) e NT 2012.002 (Manifestação do destinatário)", null, null),
    CFOP_DESTINO(Group.COERENCIA, PendencySeverity.MEDIA, "CFOP incompatível com o destino da operação",
            "Convênio s/nº de 15/12/1970 (CFOP) e Ajuste SINIEF 07/2001", null, null),
    ALIQUOTA_INTERESTADUAL(Group.COERENCIA, PendencySeverity.ALTA, "Alíquota interestadual de ICMS divergente (4% no importado)",
            "Resolução do Senado Federal 13/2012 (4% para importados) e 22/1989 (7% e 12%)", null, null),
    CRT_CST(Group.COERENCIA, PendencySeverity.MEDIA, "Código de ICMS incompatível com o regime do emitente (CRT × CST/CSOSN)",
            "MOC da NF-e (CRT 1/4 informam CSOSN; CRT 2/3 informam CST) e LC 123/2006",
            "https://www.planalto.gov.br/ccivil_03/leis/lcp/lcp123.htm", null),
    ST_CFOP_CST(Group.COERENCIA, PendencySeverity.MEDIA, "CFOP de substituição tributária com CST/CSOSN sem ST",
            "RICMS-PE (Decreto 44.650/2017), arts. 324 a 343, e Convênio ICMS 142/2018",
            "https://www.confaz.fazenda.gov.br/legislacao/convenios/2018/CV142_18", null),
    ST_SEM_CEST(Group.COERENCIA, PendencySeverity.MEDIA, "Item com ICMS-ST sem CEST",
            "Convênio ICMS 142/2018, cláusula terceira (CEST obrigatório nas mercadorias sujeitas a ST)",
            "https://www.confaz.fazenda.gov.br/legislacao/convenios/2018/CV142_18", null),
    IBSCBS_GRUPO(Group.COERENCIA, PendencySeverity.MEDIA, "NF-e de 2026 do regime normal sem o grupo IBS/CBS",
            "LC 214/2025 e NT 2025.002 (grupo IBS/CBS nos documentos a partir de 2026)",
            "https://www.planalto.gov.br/ccivil_03/leis/lcp/lcp214.htm", LocalDate.of(2026, 1, 1)),
    FATURAMENTO_ATIPICO(Group.ANOMALIA, PendencySeverity.MEDIA, "Faturamento do mês muito acima da média dos 12 meses anteriores",
            "Indicador de malha fiscal: variação brusca de faturamento (mais de 3 vezes a média)", null, null),
    NFCE_CANCELAMENTO(Group.ANOMALIA, PendencySeverity.MEDIA, "Cancelamento de NFC-e acima de 5% no mês",
            "Indicador de malha fiscal: cancelamentos de NFC-e acima do usual", null, null),
    RBT12_SUBLIMITE(Group.ANOMALIA, PendencySeverity.ALTA, "RBT12 perto ou acima do sublimite do Simples Nacional",
            "LC 123/2006, arts. 13-A e 19 (sublimite estadual de R$ 3,6 milhões)",
            "https://www.planalto.gov.br/ccivil_03/leis/lcp/lcp123.htm", null),
    RBT12_LIMITE(Group.ANOMALIA, PendencySeverity.ALTA, "Receita perto ou acima do limite do Simples Nacional / MEI",
            "LC 123/2006, art. 3º, II (limite de R$ 4,8 milhões) e art. 18-A (MEI)",
            "https://www.planalto.gov.br/ccivil_03/leis/lcp/lcp123.htm", null);

    /** How the screen groups the rules. */
    public enum Group { INTEGRIDADE, COERENCIA, ANOMALIA }

    public final Group group;
    public final PendencySeverity severity;
    public final String title;
    public final String legalBasis;
    public final String link;
    public final LocalDate validFrom;

    AuditRuleDefinition(Group group, PendencySeverity severity, String title, String legalBasis, String link, LocalDate validFrom) {
        this.group = group;
        this.severity = severity;
        this.title = title;
        this.legalBasis = legalBasis;
        this.link = link;
        this.validFrom = validFrom;
    }
}
