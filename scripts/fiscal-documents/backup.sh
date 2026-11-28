#!/usr/bin/env bash
# Backup obrigatório da remodelagem dos documentos fiscais (spec 2026-11-27, FD-RN-52, runbook passo 1).
#
# Quando: depois do passo 2 (rotinas pausadas, nada mais entra) e ANTES de subir a imagem nova (V86 a V89). É a única
# forma de volta depois da V89 (que apaga o legado). Sem este script terminar com "BACKUP CONFERIDO", o deploy não segue.
#
# O que faz:
#   1. mysqldump --single-transaction (estrutura + dados, sem triggers/rotinas/eventos) de fiscal_xml_documents,
#      fiscal_xml_document_items, service_invoices, fiscal_xml_events e fiscal_xml_sync_control;
#   2. grava o SHA-256 do arquivo ao lado (<arquivo>.sha256);
#   3. restaura o arquivo num schema descartável (CHECK_SCHEMA) e compara, tabela a tabela, COUNT(*) e as somas
#      (total_valor_total_nota, valor_servico, valor_total dos itens, total_sieg) com a origem;
#   4. apaga o schema de conferência (ou o mantém com KEEP_CHECK_SCHEMA=1, para o scripts/fiscal-documents/parity.sql);
#   5. só se conferiu: grava no schema da aplicação, em fiscal_documents_backup_checks (criada por backup-checks.sql se
#      não existir), o SHA-256 do arquivo e o COUNT(*) de cada tabela. A V89 só apaga as legacy_* se o FD_BACKUP_SHA256
#      informado for o do último registro e as legacy_* tiverem as contagens registradas (trava do FD-RN-52).
#
#
# Uso (na EC2, com o MySQL no container do compose):
#   DOCKER_CONTAINER=taxify-mysql DB_USER=root DB_NAME=<schema> OUT_DIR=/home/ec2-user/fd-backup \
#     scripts/fiscal-documents/backup.sh
# A senha é pedida no terminal (sem eco) e vai para um arquivo de opções temporário (umask 077, apagado no fim, também
# dentro do container), passado por --defaults-extra-file: nunca na linha de comando nem em variável de ambiente. No
# arquivo, o valor vai entre aspas duplas com \ e " escapados: senha com #, espaço ou aspas funciona.
# Para automação (o teste), DB_DEFAULTS_FILE aponta para um arquivo de opções [client] já pronto (permissão 600).
# Sem DOCKER_CONTAINER, usa os clientes mysql/mysqldump do host com DB_HOST (padrão 127.0.0.1) e DB_PORT (3306).
# O schema de conferência (CHECK_SCHEMA, padrão fd_backup_check) precisa começar com fd_; um schema que já existe e que
# não foi criado por este script (sem a tabela-marca fd_backup_check_marker) é recusado.
#
# Permissões (runbook 6.2, passo 1): o usuário do MySQL deste script precisa, no schema da aplicação, de SELECT nas 5
#   tabelas do dump e de CREATE + INSERT em fiscal_documents_backup_checks (a tabela é criada aqui na primeira vez,
#   por backup-checks.sql), e de CREATE/DROP nos schemas fd_* (a conferência). O usuário da aplicação (o do Flyway)
#   só lê fiscal_documents_backup_checks (SELECT), na V89. Na EC2 os dois são o root do container taxify-mysql.
#
# Depois (runbook 6.2, passo 1 -> passo 4):
#   1. A última linha é "FD_BACKUP_SHA256=<64 hex>". Copiar o valor para o env INLINE do serviço da aplicação no
#      compose DA EC2 (/home/ec2-user/docker-compose.yml, bloco environment, chave FD_BACKUP_SHA256) - não no
#      docker-compose.yml do repositório (lá o padrão fica vazio) nem num .env. Conferir com
#      `docker compose config | grep FD_BACKUP_SHA256` antes de subir a imagem nova.
#   2. Copiar o arquivo e o .sha256 para o bucket S3 privado da conta, com criptografia (por exemplo
#      aws s3 cp <arquivo> s3://<bucket>/fiscal-documents/ --sse aws:kms) e anotar o SHA-256 no registro da release.
#   3. Apagar o arquivo do host e do S3 no prazo da P-FD-08 (30 dias); tirar FD_BACKUP_SHA256 do compose depois que a
#      V89 rodou (ela não roda de novo). O arquivo tem dado de cliente: acesso só do Tony e do dev da janela.
# Se a V89 recusar (a aplicação não sobe; log "V89 refused: ..."; V86-V88 já aplicadas, as legacy_* intactas):
#   - SHA-256 ausente ou com outro valor: corrigir FD_BACKUP_SHA256 no compose da EC2, apagar a linha da V89 que
#     falhou (`flyway repair`, ou DELETE FROM flyway_schema_history WHERE version = '89' AND success = 0) e subir de
#     novo - nada foi apagado;
#   - contagens das legacy_* diferentes das registradas (algo gravou depois do backup) ou backup não registrado: o
#     backup não serve mais e não dá para refazê-lo depois do rename. Voltar com rollback.sql (estado B: devolve os
#     nomes originais, apaga as tabelas novas e as linhas 86 a 89 do histórico), subir a imagem anterior e refazer a
#     release do passo 1.
# O script não imprime dado nenhum: só nomes de tabela, contagens, o SHA-256 e OK/DIFERENTE.
set -euo pipefail
# Git Bash (Windows) would turn the container path /tmp/... into a Windows path when calling docker
export MSYS_NO_PATHCONV=1

: "${DB_NAME:?informe DB_NAME (o schema da aplicação)}"
: "${DB_USER:?informe DB_USER}"
: "${OUT_DIR:?informe OUT_DIR (fora do container)}"
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
CHECK_SCHEMA="${CHECK_SCHEMA:-fd_backup_check}"
KEEP_CHECK_SCHEMA="${KEEP_CHECK_SCHEMA:-0}"
TABLES=(fiscal_xml_documents fiscal_xml_document_items service_invoices fiscal_xml_events fiscal_xml_sync_control)
MARKER=fd_backup_check_marker
CHECKS_TABLE=fiscal_documents_backup_checks
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

[[ "$CHECK_SCHEMA" =~ ^fd_[a-z0-9_]+$ ]] || { echo "CHECK_SCHEMA precisa começar com fd_ (só minúsculas, números e _)"; exit 2; }
[[ "$CHECK_SCHEMA" != "$DB_NAME" ]] || { echo "CHECK_SCHEMA não pode ser o schema da aplicação"; exit 2; }

umask 077
local_cnf="$(mktemp)"
remote_cnf="/tmp/fd-backup-$$.cnf"
cleanup() {
  rm -f "$local_cnf"
  if [[ -n "${DOCKER_CONTAINER:-}" ]]; then docker exec "$DOCKER_CONTAINER" rm -f "$remote_cnf" >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT
# A value of the options file between double quotes, with \ and " escaped (the client reads \\ and \" back): a password
# with #, spaces or quotes is read as it is (unquoted, # starts a comment and the spaces around the value are cut).
option_value() {
  local value="${1//\\/\\\\}"
  printf '%s' "${value//\"/\\\"}"
}
if [[ -n "${DB_DEFAULTS_FILE:-}" ]]; then
  cat "$DB_DEFAULTS_FILE" > "$local_cnf"
else
  # IFS= keeps spaces at the start and at the end of the password (read trims them otherwise)
  IFS= read -rsp "senha do MySQL para $DB_USER: " password; echo
  printf '[client]\nuser="%s"\npassword="%s"\n' "$(option_value "$DB_USER")" "$(option_value "$password")" > "$local_cnf"
  unset password
fi
if [[ -n "${DOCKER_CONTAINER:-}" ]]; then
  docker exec -i "$DOCKER_CONTAINER" sh -c "umask 077 && cat > $remote_cnf" < "$local_cnf"
  cnf="$remote_cnf"
else
  cnf="$local_cnf"
fi

client() { # client <mysql|mysqldump> <args...>
  local program="$1"; shift
  if [[ -n "${DOCKER_CONTAINER:-}" ]]; then
    docker exec -i "$DOCKER_CONTAINER" "$program" --defaults-extra-file="$cnf" "$@"
  else
    "$program" --defaults-extra-file="$cnf" -h"$DB_HOST" -P"$DB_PORT" "$@"
  fi
}

prepare_check_schema() { # só apaga um schema que este script criou (tem a tabela-marca)
  local exists marked
  exists="$(client mysql -N -B -e "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = '$CHECK_SCHEMA'")"
  if [[ "$exists" != "0" ]]; then
    marked="$(client mysql -N -B -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '$CHECK_SCHEMA' AND table_name = '$MARKER'")"
    [[ "$marked" == "1" ]] || { echo "o schema $CHECK_SCHEMA já existe e não foi criado por este script: nada foi feito"; exit 2; }
    client mysql -e "DROP DATABASE \`$CHECK_SCHEMA\`"
  fi
  client mysql -e "CREATE DATABASE \`$CHECK_SCHEMA\`; CREATE TABLE \`$CHECK_SCHEMA\`.$MARKER (created_at datetime)"
}

summary() { # summary <schema>: tabela, linhas, conferência das somas (sem imprimir valores: só o hash delas)
  client mysql -N -B "$1" -e "
    SELECT 'fiscal_xml_documents', COUNT(*), SHA2(CONCAT(COALESCE(SUM(total_valor_total_nota), 0)), 256) FROM fiscal_xml_documents
    UNION ALL SELECT 'fiscal_xml_document_items', COUNT(*), SHA2(CONCAT(COALESCE(SUM(valor_total), 0)), 256) FROM fiscal_xml_document_items
    UNION ALL SELECT 'service_invoices', COUNT(*), SHA2(CONCAT(COALESCE(SUM(valor_servico), 0)), 256) FROM service_invoices
    UNION ALL SELECT 'fiscal_xml_events', COUNT(*), SHA2(CONCAT(SUM(registered)), 256) FROM fiscal_xml_events
    UNION ALL SELECT 'fiscal_xml_sync_control', COUNT(*), SHA2(CONCAT(COALESCE(SUM(total_sieg), 0), '/', COALESCE(SUM(total_importado), 0)), 256)
      FROM fiscal_xml_sync_control"
}

mkdir -p "$OUT_DIR"
file="$OUT_DIR/fiscal-documents-backup-$(date +%Y%m%d-%H%M%S).sql"

prepare_check_schema

echo "1/4 dump de ${#TABLES[@]} tabelas"
client mysqldump --single-transaction --skip-triggers --skip-routines --skip-events --no-tablespaces \
  --set-gtid-purged=OFF --hex-blob --default-character-set=utf8mb4 "$DB_NAME" "${TABLES[@]}" > "$file"
[[ -s "$file" ]] || { echo "dump vazio"; exit 1; }

echo "2/4 SHA-256"
(cd "$OUT_DIR" && sha256sum "$(basename "$file")" > "$(basename "$file").sha256")
cat "$file.sha256"

echo "3/4 restauração de conferência em $CHECK_SCHEMA"
source_summary="$(summary "$DB_NAME")"
client mysql "$CHECK_SCHEMA" < "$file"
check_summary="$(summary "$CHECK_SCHEMA")"

echo "4/4 comparação"
status=0
while IFS=$'\t' read -r table rows hash; do
  other="$(printf '%s\n' "$check_summary" | awk -F'\t' -v t="$table" '$1 == t {print $2 "\t" $3}')"
  if [[ "$other" == "$rows"$'\t'"$hash" ]]; then
    echo "  $table: $rows linhas - OK"
  else
    echo "  $table: $rows linhas na origem, $(printf '%s' "$other" | cut -f1) na restauração - DIFERENTE"
    status=1
  fi
done <<< "$source_summary"

if [[ "$KEEP_CHECK_SCHEMA" != "1" ]]; then
  client mysql -e "DROP DATABASE IF EXISTS \`$CHECK_SCHEMA\`"
fi

if [[ $status -ne 0 ]]; then
  echo "BACKUP NÃO CONFERE: o deploy não passa do passo 1 (CA-FD-26). Arquivo: $file"
  exit 1
fi
# FD-RN-52: the record V89 checks (FD_BACKUP_SHA256 = this SHA-256, legacy rows = these counts); only for a checked backup
sha="$(cut -d' ' -f1 "$file.sha256")"
[[ "$sha" =~ ^[0-9a-f]{64}$ ]] || { echo "SHA-256 inválido em $file.sha256"; exit 1; }
values=""
while IFS=$'\t' read -r table rows _; do
  [[ "$table" =~ ^[a-z_]+$ && "$rows" =~ ^[0-9]+$ ]] || { echo "contagem inválida para o registro do backup"; exit 1; }
  values+="${values:+, }('$sha', '$table', $rows, NOW())"
done <<< "$source_summary"
client mysql "$DB_NAME" < "$SCRIPT_DIR/backup-checks.sql"
client mysql "$DB_NAME" -e "INSERT INTO $CHECKS_TABLE (backup_sha256, table_name, row_count, created_at) VALUES $values"
echo "registrado em $CHECKS_TABLE (a V89 confere o FD_BACKUP_SHA256 e as contagens das legacy_* com este registro)"
echo "BACKUP CONFERIDO: $file"
echo "FD_BACKUP_SHA256=$sha"
