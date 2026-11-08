#!/usr/bin/env bash
# Crea los tópicos de la especificación §5.5 y su tópico de mensajes venenosos
# <tópico>.dlt con las mismas particiones (ADR 0010). Es idempotente y lista lo creado.
# Se ejecuta en el servicio kafka-topicos del compose, o a mano:
#   docker compose -f infra/docker-compose.yml exec kafka bash /opt/fraude/crear-topicos.sh
#
# Factor de replicación 1: solo aceptable en este docker-compose local. Fuera de local se
# exige replicación >= 3 y min.insync.replicas=2 (CLAUDE.md, reglas de Kafka).
set -euo pipefail

BOOTSTRAP="${BOOTSTRAP:-kafka:19092}"
KT=/opt/kafka/bin/kafka-topics.sh

crear() { # nombre particiones
  "$KT" --bootstrap-server "$BOOTSTRAP" --create --if-not-exists \
    --topic "$1" --partitions "$2" --replication-factor 1
}

TOPICOS="transacciones.creadas:6 decisiones.sospechosas:6 casos.eventos:3"

for t in $TOPICOS; do
  crear "${t%%:*}" "${t##*:}"
  crear "${t%%:*}.dlt" "${t##*:}"
done

echo "Tópicos:"
for t in $TOPICOS; do
  for nombre in "${t%%:*}" "${t%%:*}.dlt"; do
    n=$("$KT" --bootstrap-server "$BOOTSTRAP" --describe --topic "$nombre" | grep -c 'Partition: ')
    echo "  $nombre  particiones=$n"
  done
done
