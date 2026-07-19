#!/usr/bin/env bash
#
# Keeloke TV Server - quita toda la promoción/mensajería de "Ant Media
# Enterprise Edition" del panel de administración web:
#
#   1. El banner "Get Enterprise Edition for Ultra Low Latency Streaming"
#      que aparece arriba de cada app (LiveApp/WebRTCApp/live).
#   2. El chequeo de estado de licencia que dispara los popups "Your
#      license expires in N days" / "Invalid License - Please Validate
#      Your License" en cada carga de la app (una condición de carrera
#      del propio código de Ant Media: el flag isEnterpriseEdition arranca
#      en true por defecto en el componente y el chequeo puede dispararse
#      antes de que llegue la respuesta real del backend).
#   3. Los 8 avisos por función individual en la pestaña Settings ("Get
#      Enterprise Edition to use Adaptive Streaming feature.", etc.)
#
# El panel (webapps/root/*.js) es un bundle Angular precompilado que viene
# con la distribución binaria de Ant Media Server, no es parte del código
# fuente Java de este repo - por eso este parche opera sobre texto dentro
# del JS ya compilado en vez de una recompilación. Es idempotente: si algo
# ya fue quitado, no hace nada en ese punto.
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

shopt -s nullglob
files=("$ROOT"/main-es2015.*.js "$ROOT"/main-es5.*.js)
shopt -u nullglob

if [ ${#files[@]} -eq 0 ]; then
  echo "No se encontraron bundles main-es2015.*.js / main-es5.*.js en $ROOT" >&2
  exit 1
fi

for f in "${files[@]}"; do
  python3 - "$f" <<'PYEOF'
import re
import sys

path = sys.argv[1]
with open(path, "r", encoding="utf-8") as fh:
    content = fh.read()

total = 0

# 1. Banner "Get Enterprise Edition for Ultra Low Latency Streaming"
for prefix in ("f", "d"):
    needle = (
        f'{prefix}.Lc(2,"Get "),{prefix}.Tb(3,"a",34),'
        f'{prefix}.Lc(4,"Enterprise Edition"),{prefix}.Sb(),'
        f'{prefix}.Lc(5," for Ultra Low Latency Streaming")'
    )
    replacement = (
        f'{prefix}.Lc(2,""),{prefix}.Tb(3,"a",34),'
        f'{prefix}.Lc(4,""),{prefix}.Sb(),'
        f'{prefix}.Lc(5,"")'
    )
    if needle in content:
        content = content.replace(needle, replacement)
        total += 1

# 2. License-status check guard (es2015 native template call form)
needle2 = "this.isEnterpriseEdition&&this.restService.getLicenseStatus(t).subscribe("
if needle2 in content:
    content = content.replace(
        needle2, "!1&&this.restService.getLicenseStatus(t).subscribe("
    )
    total += 1

# 3a. Per-feature "Get ...Enterprise Edition... to use X feature." messages
#     (es2015 native tagged-template form)
pattern_es2015 = re.compile(
    r'Get \$\{"\\ufffd#4\\ufffd"\}:START_LINK:Enterprise Edition'
    r'\$\{"\\ufffd/#4\\ufffd"\}:CLOSE_LINK:[^`]*'
)
content, n = pattern_es2015.subn("", content)
total += n

# 3b. Same messages, es5 compiled-array form
pattern_es5 = re.compile(
    r'Get ",":START_LINK:Enterprise Edition",":CLOSE_LINK: to use [^"]*"'
)
content, n = pattern_es5.subn('""', content)
total += n

if total:
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(content)
    print(f"parcheado ({total} cambios): {path}")
else:
    print(f"sin cambios (ya estaba limpio o el bundle cambió de estructura): {path}")
PYEOF
done

echo "Listo. Recarga el panel con hard-refresh (cmd+shift+r / ctrl+shift+r)."
