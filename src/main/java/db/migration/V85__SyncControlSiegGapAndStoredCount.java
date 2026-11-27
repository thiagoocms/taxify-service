package db.migration;

/**
 * Economia de chamadas ao SIEG e painel da captura (decisão do Tony de 2026-11-27), por lado do controle:
 * <ul>
 *   <li>{@code *_known_gap} / {@code *_known_gap_month}: a diferença (total do SIEG − notas que o SIEG entregou) que
 *   sobrou depois de uma passada completa desde a primeira página, e o mês (yyyy-MM) dela. A reconciliação só roda de
 *   novo quando a diferença do mesmo mês aumenta;</li>
 *   <li>{@code *_stored_any_source}: as notas do lado na janela que o Taxify tem, de qualquer origem (SIEG, SEFAZ,
 *   upload), na última verificação - o painel mostra ao lado de "vindas do SIEG / total do SIEG".</li>
 * </ul>
 * Colunas nulas numa tabela de uma linha por empresa: seguras com a versão anterior rodando junto (ela não as lê).
 */
public class V85__SyncControlSiegGapAndStoredCount extends TableMigration {

    public V85__SyncControlSiegGapAndStoredCount() {
        super("fiscal_xml_sync_control");

        column("received_known_gap_month", "char(7)");
        column("received_known_gap", "int");
        column("issued_known_gap_month", "char(7)");
        column("issued_known_gap", "int");
        column("received_stored_any_source", "int");
        column("issued_stored_any_source", "int");
    }
}
