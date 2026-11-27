package db.migration;

/**
 * Reserva de uma rodada diária de rotina entre instâncias (spec 2026-11-27, F5a, CE-RN-08 a), genérica: uma linha por
 * rotina e dia de Recife. A chave única deixa só uma instância inserir; a outra vê a duplicidade e não roda. Uma
 * rodada que não terminou ({@code finished_at} nulo) e começou há tempo demais pode ser assumida com um UPDATE
 * condicional. Tabela nova e pequena (1 linha por rotina por dia).
 */
public class V82__RoutineRuns extends TableMigration {

    public V82__RoutineRuns() {
        super("routine_runs");

        column("id", "varchar(36) not null");
        column("routine", "varchar(40) not null");
        column("run_date", "date not null");
        column("instance_id", "varchar(60)");
        column("started_at", "datetime(6) not null");
        column("finished_at", "datetime(6)");
        column("processed", "int");
        column("failed", "int");
        column("deleted", "bit not null");
        column("created_by", "varchar(50) not null");
        column("created_date", "datetime(6) not null");
        column("last_modified_by", "varchar(50)");
        column("last_modified_date", "datetime(6)");

        unique("uk_routine_runs_routine_date", "routine", "run_date");
    }
}
