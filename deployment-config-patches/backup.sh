#!/usr/bin/env bash
#
# Keeloke TV Server - respaldo de datos.
#
# Respalda:
#   - Las bases MapDB del servidor (*.db en SERVER_HOME) - streams,
#     configuración de apps, usuarios.
#   - El snapshot RDB + carpeta AOF de Redis - estado de cluster-registry,
#     security, tenant-quota y conference-rooms plugins.
#
# No para el servidor: MapDB permite copiar los archivos en caliente en la
# práctica (son archivos append-only mapeados), y Redis con BGSAVE tampoco
# bloquea. No es un respaldo "transaccionalmente perfecto" a nivel de
# milisegundo, pero es consistente para recuperación ante desastres.
#
# Uso:
#   ./backup.sh [SERVER_HOME] [BACKUP_DIR]
#
# SERVER_HOME = directorio del servidor instalado (por defecto /usr/local/antmedia)
# BACKUP_DIR  = dónde guardar los respaldos (por defecto /var/backups/keeloke-tv)
#
# Pensado para correr por cron, ej. cada 6 horas:
#   0 */6 * * * /usr/local/antmedia/deployment-config-patches/backup.sh

set -euo pipefail

SERVER_HOME="${1:-${SERVER_HOME:-/usr/local/antmedia}}"
BACKUP_DIR="${2:-${BACKUP_DIR:-/var/backups/keeloke-tv}}"
REDIS_DATA_DIR="${REDIS_DATA_DIR:-}"
TIMESTAMP="${KEELOKE_BACKUP_TIMESTAMP:-$(date +%Y%m%d-%H%M%S)}"
DEST="$BACKUP_DIR/$TIMESTAMP"

mkdir -p "$DEST"

echo "== Respaldando bases MapDB de $SERVER_HOME =="
db_found=0
for f in "$SERVER_HOME"/*.db; do
  [ -f "$f" ] || continue
  cp "$f" "$DEST/"
  echo "  copiado: $(basename "$f")"
  db_found=1
done
if [ "$db_found" = "0" ]; then
  echo "  (no se encontraron archivos *.db en $SERVER_HOME)"
fi

echo "== Respaldando Redis =="
if command -v redis-cli >/dev/null 2>&1; then
  redis-cli BGSAVE >/dev/null 2>&1 || echo "  (BGSAVE falló - ¿Redis requiere contraseña? usa REDISCLI_AUTH=... o --pass)"
  sleep 2
  rdir="$REDIS_DATA_DIR"
  if [ -z "$rdir" ]; then
    rdir="$(redis-cli CONFIG GET dir 2>/dev/null | tail -1)"
  fi
  if [ -n "$rdir" ] && [ -f "$rdir/dump.rdb" ]; then
    cp "$rdir/dump.rdb" "$DEST/redis-dump.rdb"
    echo "  copiado: redis-dump.rdb (desde $rdir)"
  else
    echo "  (no se encontró dump.rdb en '$rdir' - ajusta REDIS_DATA_DIR)"
  fi
  if [ -n "$rdir" ] && [ -d "$rdir/appendonlydir" ]; then
    cp -r "$rdir/appendonlydir" "$DEST/redis-appendonlydir"
    echo "  copiado: appendonlydir/"
  fi
else
  echo "  (redis-cli no encontrado, se omite el respaldo de Redis)"
fi

echo "== Comprimiendo =="
tar -czf "$BACKUP_DIR/keeloke-backup-$TIMESTAMP.tar.gz" -C "$BACKUP_DIR" "$TIMESTAMP"
rm -rf "$DEST"
echo "Listo: $BACKUP_DIR/keeloke-backup-$TIMESTAMP.tar.gz"

# Retención simple: borra respaldos comprimidos de más de 14 días
find "$BACKUP_DIR" -maxdepth 1 -name 'keeloke-backup-*.tar.gz' -mtime +14 -delete 2>/dev/null || true
