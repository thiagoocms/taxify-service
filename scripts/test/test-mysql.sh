#!/usr/bin/env bash
# MySQL 8.4 descartável para rodar a suíte de testes, igual ao da EC2 (mesma imagem e mesmas opções do
# docker-compose.yml). Cada agente/QA usa o seu, numa porta própria, então as suítes rodam em paralelo sem
# disputar conexões (o MySQL local 5.7 é compartilhado e limita em 151) e testam na versão de produção.
#
# Uso:
#   scripts/test/test-mysql.sh start <nome> <porta>   # sobe taxify-test-<nome> em localhost:<porta> e espera ficar pronto
#   scripts/test/test-mysql.sh stop  <nome>           # apaga o container (e os dados; tmpfs)
#   scripts/test/test-mysql.sh env   <nome> <porta>   # imprime as variáveis para a suíte
#
# Depois do start, rode a suíte com:
#   DB_HOST=localhost DB_PORT=<porta> DB_USER=root DB_PASSWORD=root DB_NAME=taxify_test ./mvnw test
# Os testes de integração só rodam com DB_HOST=localhost e conferem a URL JDBC: nunca aponte para a EC2.
#
# Portas sugeridas para não colidir: 3401, 3402, 3403... (uma por agente). Nada de dado real: o banco nasce vazio
# e some no stop (dados em tmpfs, mais rápido e sem volume esquecido).
set -euo pipefail

cmd="${1:-}"; name="${2:-}"; port="${3:-}"
[[ -n "$cmd" && -n "$name" ]] || { sed -n '2,15p' "$0"; exit 2; }
container="taxify-test-${name}"

case "$cmd" in
  start)
    [[ -n "$port" ]] || { echo "informe a porta"; exit 2; }
    docker rm -f "$container" >/dev/null 2>&1 || true
    docker run -d --name "$container" \
      -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=taxify_test \
      -p "127.0.0.1:${port}:3306" \
      --tmpfs /var/lib/mysql:rw,size=4g \
      mysql:8.4 \
      --innodb-buffer-pool-size=256M --max-connections=300 >/dev/null
    printf 'aguardando %s em localhost:%s' "$container" "$port"
    for _ in $(seq 1 90); do
      if docker exec "$container" mysql -uroot -proot -e 'SELECT 1' >/dev/null 2>&1; then
        echo " - pronto (MySQL $(docker exec "$container" mysql -uroot -proot -N -e 'SELECT VERSION()' 2>/dev/null))"
        exit 0
      fi
      printf '.'; sleep 2
    done
    echo " - não subiu"; docker logs --tail 30 "$container"; exit 1 ;;
  stop)
    docker rm -f "$container" >/dev/null 2>&1 && echo "removido $container" || echo "$container não existia" ;;
  env)
    echo "DB_HOST=localhost DB_PORT=${port:-?} DB_USER=root DB_PASSWORD=root DB_NAME=taxify_test" ;;
  *)
    echo "comando desconhecido: $cmd"; exit 2 ;;
esac
