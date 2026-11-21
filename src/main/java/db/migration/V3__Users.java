package db.migration;

/** Usuários do escritório. */
public class V3__Users extends TableMigration {

    public V3__Users() {
        super("users");

        column("id", "varchar(36) not null");
        column("deleted", "bit not null");
        column("created_date", "datetime(6) not null");
        column("last_modified_date", "datetime(6)");
        column("profile", "varchar(20) not null");
        column("accounting_firm_id", "varchar(36) not null");
        column("document_id", "varchar(36) not null");
        column("created_by", "varchar(50) not null");
        column("last_modified_by", "varchar(50)");
        column("login", "varchar(50) not null");
        column("name", "varchar(150) not null");
        column("email", "varchar(300) not null");
        column("password", "varchar(255) not null");

        // stored as @Enumerated(ORDINAL) before - converted to the constant names
        ordinalEnum("profile", "ADMIN", "EMPLOYEE", "USER");

        index("idx_users_email", "email");
        unique("uk_users_login", "login");
        unique("uk_users_document", "document_id");
        foreignKey("fk_users_accounting_firm", "accounting_firm_id", "accounting_firms");
        foreignKey("fk_users_document", "document_id", "documents");
    }
}
