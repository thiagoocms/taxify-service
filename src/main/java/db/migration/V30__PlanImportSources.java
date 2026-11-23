package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Statement;

/**
 * Importação de notas: FISCAL_XML_IMPORT põe a empresa no agendador e a fonte passa a vir das
 * integrações do plano (SIEG_INTEGRATION, SEFAZ_INTEGRATION ou as duas). Até aqui o
 * FISCAL_XML_IMPORT sozinho já sincronizava pelo SIEG - os planos nesse caso ganham a
 * SIEG_INTEGRATION, para nenhuma empresa parar de receber notas com a mudança.
 */
public class V30__PlanImportSources extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        try (Statement statement = context.getConnection().createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO plan_services (plan_id, service)
                    SELECT ps.plan_id, 'SIEG_INTEGRATION'
                      FROM plan_services ps
                     WHERE ps.service = 'FISCAL_XML_IMPORT'
                       AND NOT EXISTS (SELECT 1 FROM plan_services other
                                        WHERE other.plan_id = ps.plan_id
                                          AND other.service IN ('SIEG_INTEGRATION', 'SEFAZ_INTEGRATION'))
                    """);
        }
    }
}
