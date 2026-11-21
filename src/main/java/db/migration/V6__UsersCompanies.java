package db.migration;

/** Carteira: empresas atendidas por cada usuário. */
public class V6__UsersCompanies extends TableMigration {

    public V6__UsersCompanies() {
        super("users_companies");

        column("deleted", "bit not null");
        column("created_date", "datetime(6) not null");
        column("last_modified_date", "datetime(6)");
        column("company_id", "varchar(36) not null");
        column("user_id", "varchar(36) not null");
        column("created_by", "varchar(50) not null");
        column("last_modified_by", "varchar(50)");
        column("role", "varchar(50) not null");

        primaryKey("company_id", "user_id");

        foreignKey("fk_users_companies_company", "company_id", "companies");
        foreignKey("fk_users_companies_user", "user_id", "users");
    }
}
