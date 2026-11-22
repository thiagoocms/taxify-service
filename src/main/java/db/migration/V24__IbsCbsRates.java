package db.migration;

/**
 * Alíquotas de referência de IBS/CBS por ano (em %). Semeia 2026, o ano de teste da
 * Reforma Tributária (LC 214/2025): CBS 0,9%, IBS estadual 0,1%, IBS municipal 0%.
 * Os anos seguintes entram por cadastro quando forem publicados.
 */
public class V24__IbsCbsRates extends TableMigration {

    public V24__IbsCbsRates() {
        super("ibs_cbs_rates");

        column("ano", "integer not null");
        column("aliquota_cbs", "decimal(7,4) not null");
        column("aliquota_ibs_uf", "decimal(7,4) not null");
        column("aliquota_ibs_mun", "decimal(7,4) not null");

        primaryKey("ano");
    }

    @Override
    protected void beforeKeys() {
        if (count("SELECT COUNT(*) FROM ibs_cbs_rates WHERE ano = 2026") == 0) {
            jdbc.update("INSERT INTO ibs_cbs_rates (ano, aliquota_cbs, aliquota_ibs_uf, aliquota_ibs_mun) VALUES (2026, 0.9, 0.1, 0)");
            log.info("Seeded the 2026 IBS/CBS rates");
        }
    }
}
