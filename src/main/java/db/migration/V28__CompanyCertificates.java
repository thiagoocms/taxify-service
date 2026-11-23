package db.migration;

/** Certificado A1 de cada empresa: o .pfx fica no storage (S3) cifrado; aqui a senha cifrada e a validade. */
public class V28__CompanyCertificates extends TableMigration {

    public V28__CompanyCertificates() {
        super("company_certificates");

        column("id", "varchar(36) not null");
        column("company_id", "varchar(36) not null");
        column("file_path", "varchar(500) not null");
        column("password_encrypted", "varchar(500) not null");
        column("cnpj", "varchar(14)");
        column("subject_name", "varchar(300)");
        column("valid_from", "datetime(6)");
        column("valid_until", "datetime(6) not null");
        column("deleted", "bit not null");
        column("created_by", "varchar(50) not null");
        column("created_date", "datetime(6) not null");
        column("last_modified_by", "varchar(50)");
        column("last_modified_date", "datetime(6)");

        unique("uk_company_certificates_company", "company_id");
        foreignKey("fk_company_certificates_company", "company_id", "companies");
    }
}
