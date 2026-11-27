package db.migration;

/**
 * Senha e sessões (spec 2026-11-27, SN-RN-09 e SN-RN-11): a versão da sessão do usuário (o claim {@code sv} do JWT; um
 * token com outra versão responde 401), a hora da última troca de senha e o contador de erros da senha atual na troca
 * pelo próprio usuário (com o início da janela de 60 minutos). Os usuários existentes ficam com versão 0 e contador 0 -
 * o token emitido antes do deploy, sem {@code sv}, vale como 0 e continua passando.
 */
public class V71__UserSessionVersion extends TableMigration {

    public V71__UserSessionVersion() {
        super("users");

        column("session_version", "int not null default 0");
        column("password_changed_at", "datetime(6)");
        column("password_fail_count", "int not null default 0");
        column("password_fail_window_start", "datetime(6)");
    }
}
