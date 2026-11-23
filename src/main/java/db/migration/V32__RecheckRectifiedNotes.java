package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Statement;

/**
 * O CFOP da regra passou a seguir a operação de cada nota (1102 vira 2102 numa compra
 * interestadual) e a regra pode ser de um fornecedor. As notas já retificadas foram geradas
 * com o CFOP fixo da regra: a marca da última checagem é limpa para que a próxima retificação
 * (agendada ou pela tela) refaça o arquivo com o CFOP da operação. Até lá a nota volta a mostrar
 * o CFOP emitido e a divergência, como qualquer nota ainda não retificada.
 */
public class V32__RecheckRectifiedNotes extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        try (Statement statement = context.getConnection().createStatement()) {
            statement.executeUpdate("""
                    UPDATE fiscal_xml_documents
                       SET last_rectification_checked_at = NULL
                     WHERE rectified_file_path IS NOT NULL
                    """);
        }
    }
}
