#!/usr/bin/env bash
# Crea los tópicos de la especificación §5.5 (idempotente) y los lista con sus
# particiones. Se ejecuta solo en el servicio kafka-topicos del compose, o a mano:
#   docker compose -f infra/docker-compose.yml exec kafka bash /opt/fraude/crear-topicos.sh
set -euo pipefail

BOOTSTRAP="${BOOTSTRAP:-kafka:19092}"
KT=/opt/kafka/bin/kafka-topics.sh

crear() { # nombre particiones
  "$KT" --bootstrap-server "$BOOTSTRAP" --create --if-not-exists \
    --topic "$1" --partitions "$2" --replication-factor 1
}

crear transacciones.creadas 6
crear decisiones.sospechosas 6
crear casos.eventos 3

echo "Tópicos:"
for t in transacciones.creadas decisiones.sospechosas casos.eventos; do
  n=$("$KT" --bootstrap-server "$BOOTSTRAP" --describe --topic "$t" | grep -c 'Partition: ')
  echo "  $t  particiones=$n"
done
