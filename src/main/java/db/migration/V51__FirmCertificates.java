package db.migration;

/**
 * Certificado A1 do contador (e-CPF) do escritório: entra no e-Fisco de PE pelas empresas da carteira para buscar o
 * extrato do Fronteira. Como o da empresa, o .pfx fica no storage cifrado; aqui a senha cifrada, o CPF e a validade.
 */
public class V51__FirmCertificates extends TableMigration {

    public V51__FirmCertificates() {
        super("firm_certificates");

        column("id", "varchar(36) not null");
        column("accounting_firm_id", "varchar(36) not null");
        column("file_path", "varchar(500) not null");
        column("password_encrypted", "varchar(500) not null");
        column("cpf", "varchar(11)");
        column("subject_name", "varchar(300)");
        column("valid_from", "datetime(6)");
        column("valid_until", "datetime(6) not null");
        // a última sincronização com o e-Fisco: quando, como terminou e o resumo
        column("last_sync_at", "datetime(6)");
        column("last_sync_status", "varchar(30)");
        column("last_sync_detail", "varchar(1000)");
        column("deleted", "bit not null");
        column("created_by", "varchar(50) not null");
        column("created_date", "datetime(6) not null");
        column("last_modified_by", "varchar(50)");
        column("last_modified_date", "datetime(6)");

        unique("uk_firm_certificates_firm", "accounting_firm_id");
        foreignKey("fk_firm_certificates_firm", "accounting_firm_id", "accounting_firms");
    }
}
