package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Taxify SaaS fase 1 (F1-RN-13): todo escritório que já existia na virada fica liberado sem prazo - ninguém é
 * bloqueado no deploy - e entra na fila de revisão do console da plataforma. Ver {@link ExistingFirmsRelease}.
 */
public class V63__ReleaseExistingFirms extends BaseJavaMigration {

    @Override
    public void migrate(Context context) {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(context.getConnection(), true));
        new ExistingFirmsRelease(jdbc).run(LocalDateTime.now(ZoneId.of("America/Recife")));
    }
}
