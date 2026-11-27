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
#   4. apaga o schema de conferência (ou o mantém com KEEP_CHECK_SCHEMA=1, para o scripts/fiscal-documents/parity.sql).
#
# Uso (na EC2, com o MySQL no container do compose; a senha vai por variável, nunca na linha de comando):
#   export MYSQL_PWD='...'            # lida pelo mysql/mysqldump; repassada ao container por "docker exec -e MYSQL_PWD"
#   DOCKER_CONTAINER=taxify-mysql DB_USER=root DB_NAME=<schema> OUT_DIR=/home/ec2-user/fd-backup \
#     scripts/fiscal-documents/backup.sh
# Sem DOCKER_CONTAINER, usa os clientes mysql/mysqldump do host com DB_HOST (padrão 127.0.0.1) e DB_PORT (3306).
#
# Depois: copiar o arquivo e o .sha256 para o bucket S3 privado da conta, com criptografia (por exemplo
#   aws s3 cp <arquivo> s3://<bucket>/fiscal-documents/ --sse aws:kms), anotar o SHA-256 no registro da release e
#   apagar o arquivo do host e do S3 no prazo da P-FD-08 (30 dias). O arquivo tem dado de cliente: acesso só do Tony e
#   do dev da janela. O script não imprime dado nenhum: só nomes de tabela, contagens e OK/DIFERENTE.
set -euo pipefail

: "${DB_NAME:?informe DB_NAME (o schema da aplicação)}"
: "${DB_USER:?informe DB_USER}"
: "${OUT_DIR:?informe OUT_DIR (fora do container)}"
: "${MYSQL_PWD:?exporte MYSQL_PWD (a senha não vai na linha de comando)}"
export MYSQL_PWD
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
CHECK_SCHEMA="${CHECK_SCHEMA:-fd_backup_check}"
KEEP_CHECK_SCHEMA="${KEEP_CHECK_SCHEMA:-0}"
TABLES=(fiscal_xml_documents fiscal_xml_document_items service_invoices fiscal_xml_events fiscal_xml_sync_control)

[[ "$CHECK_SCHEMA" != "$DB_NAME" ]] || { echo "CHECK_SCHEMA não pode ser o schema da aplicação"; exit 2; }
[[ "$CHECK_SCHEMA" =~ ^[a-z0-9_]+$ ]] || { echo "CHECK_SCHEMA inválido"; exit 2; }

client() { # client <mysql|mysqldump> <args...>
  local program="$1"; shift
  if [[ -n "${DOCKER_CONTAINER:-}" ]]; then
    docker exec -i -e MYSQL_PWD "$DOCKER_CONTAINER" "$program" -u"$DB_USER" "$@"
  else
    "$program" -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" "$@"
  fi
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

umask 077
mkdir -p "$OUT_DIR"
file="$OUT_DIR/fiscal-documents-backup-$(date +%Y%m%d-%H%M%S).sql"

echo "1/4 dump de ${#TABLES[@]} tabelas"
client mysqldump --single-transaction --skip-triggers --skip-routines --skip-events --no-tablespaces \
  --set-gtid-purged=OFF --hex-blob --default-character-set=utf8mb4 "$DB_NAME" "${TABLES[@]}" > "$file"
[[ -s "$file" ]] || { echo "dump vazio"; exit 1; }

echo "2/4 SHA-256"
(cd "$OUT_DIR" && sha256sum "$(basename "$file")" > "$(basename "$file").sha256")
cat "$file.sha256"

echo "3/4 restauração de conferência em $CHECK_SCHEMA"
source_summary="$(summary "$DB_NAME")"
client mysql -e "DROP DATABASE IF EXISTS \`$CHECK_SCHEMA\`; CREATE DATABASE \`$CHECK_SCHEMA\`"
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
echo "BACKUP CONFERIDO: $file"
