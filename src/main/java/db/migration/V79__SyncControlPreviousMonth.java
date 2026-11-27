package db.migration;

/**
 * Painel da captura (spec 2026-11-27, CP-RN-22, decisão P-CP-06): como o último mês verificado fechou, por lado. Na
 * virada do mês a rotina passa a contar só o mês novo; estas colunas guardam os contadores do mês anterior, copiados
 * antes de serem trocados. Nulas até a primeira virada depois do deploy.
 */
public class V79__SyncControlPreviousMonth extends TableMigration {

    public V79__SyncControlPreviousMonth() {
        super("fiscal_xml_sync_control");

        column("received_prev_month", "char(7)");
        column("received_prev_total_sieg", "int");
        column("received_prev_imported", "int");
        column("issued_prev_month", "char(7)");
        column("issued_prev_total_sieg", "int");
        column("issued_prev_imported", "int");
    }
}
