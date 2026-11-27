package db.migration;

/**
 * Recuperação dos meses bloqueados (decisão do Tony sobre o QA-02): o período que ainda falta recuperar depois que o
 * escritório voltou a ter acesso total. Juntado quando há novo bloqueio antes de concluir; limpo ao concluir.
 */
public class V65__FirmSubscriptionRecovery extends TableMigration {

    public V65__FirmSubscriptionRecovery() {
        super("firm_subscriptions");

        column("recovery_from", "date");
        column("recovery_until", "date");
    }
}
