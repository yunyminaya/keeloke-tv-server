#!/usr/bin/env bash
#
# Keeloke TV Server - quita el banner "Get Enterprise Edition for Ultra Low
# Latency Streaming" del panel de administración web.
#
# El panel (webapps/root/*.js) es un bundle Angular precompilado que viene
# con la distribución binaria de Ant Media Server, no es parte del código
# fuente Java de este repo - por eso este parche opera sobre texto dentro
# del JS ya compilado en vez de una recompilación. Es idempotente: si el
# banner ya fue quitado, no hace nada.
#
# Uso:
#   ./strip-enterprise-banner.sh [SERVER_HOME]
#
# SERVER_HOME = directorio del servidor instalado (por defecto /usr/local/antmedia).

set -euo pipefail

SERVER_HOME="${1:-${SERVER_HOME:-/usr/local/antmedia}}"
ROOT="$SERVER_HOME/webapps/root"

if [ ! -d "$ROOT" ]; then
  echo "No se encontró $ROOT - ¿SERVER_HOME es correcto?" >&2
  exit 1
fi

patched=0
for f in "$ROOT"/main-es2015.*.js "$ROOT"/main-es5.*.js; do
  [ -f "$f" ] || continue
  for prefix in f d; do
    needle="${prefix}.Lc(2,\"Get \"),${prefix}.Tb(3,\"a\",34),${prefix}.Lc(4,\"Enterprise Edition\"),${prefix}.Sb(),${prefix}.Lc(5,\" for Ultra Low Latency Streaming\")"
    replacement="${prefix}.Lc(2,\"\"),${prefix}.Tb(3,\"a\",34),${prefix}.Lc(4,\"\"),${prefix}.Sb(),${prefix}.Lc(5,\"\")"
    if grep -qF "$needle" "$f"; then
      python3 - "$f" "$needle" "$replacement" <<'PYEOF'
import sys
path, needle, replacement = sys.argv[1], sys.argv[2], sys.argv[3]
with open(path, "r", encoding="utf-8") as fh:
    content = fh.read()
content = content.replace(needle, replacement)
with open(path, "w", encoding="utf-8") as fh:
    fh.write(content)
PYEOF
      echo "parcheado: $f"
      patched=1
    fi
  done
done

if [ "$patched" = "1" ]; then
  echo "Listo. Recarga el panel con hard-refresh (cmd+shift+r / ctrl+shift+r)."
else
  echo "Nada que parchear (ya estaba limpio, o el bundle cambió de nombre/estructura)."
fi
