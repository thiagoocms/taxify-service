package db.migration;

/** Perfis de acesso de cada usuário (FISCAL, FINANCIAL...). */
public class V4__UserAccessProfiles extends TableMigration {

    public V4__UserAccessProfiles() {
        super("user_access_profiles");

        column("access_profile", "varchar(30) not null");
        column("user_id", "varchar(36) not null");

        primaryKey("access_profile", "user_id");

        foreignKey("fk_user_access_profiles_user", "user_id", "users");
    }
}
