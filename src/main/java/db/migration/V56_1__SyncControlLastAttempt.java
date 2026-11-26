package db.migration;

/**
 * Fila persistida da captura da SIEG: quando a empresa teve a última tentativa (sucesso, falha ou pulo por estar em
 * dia). O FiscalXmlSyncScheduler escolhe o lote pelas tentativas mais antigas - nulas primeiro - em vez de um cursor
 * em memória que voltava a zero a cada restart. Nula nas linhas que já existiam: elas entram pela verificação real.
 */
public class V56_1__SyncControlLastAttempt extends TableMigration {

    public V56_1__SyncControlLastAttempt() {
        super("fiscal_xml_sync_control");

        column("last_attempt_at", "datetime(6)");

        index("idx_fiscal_xml_sync_control_last_attempt", "last_attempt_at");
    }
}
