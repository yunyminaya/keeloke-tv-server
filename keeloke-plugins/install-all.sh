#!/usr/bin/env bash
#
# Keeloke TV Server - compila e instala TODOS los plugins con un solo comando.
#
#   ./install-all.sh [SERVER_HOME]
#
# SERVER_HOME = directorio del servidor instalado (por defecto /usr/local/antmedia).
# También configurable por variables de entorno:
#   SERVER_HOME   ruta del servidor (default /usr/local/antmedia)
#   AMS_JAR       ruta a un ant-media-server*.jar para resolver la dependencia
#                 si Maven Central no tiene la versión (se auto-detecta si no se da)
#   RESTART=1     reinicia el servicio systemd 'antmedia' al terminar
#   SKIP_BUILD=1  no compila, solo instala los .jar ya compilados
#
set -euo pipefail

# ---------- ubicación y config ----------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

SERVER_HOME="${1:-${SERVER_HOME:-/usr/local/antmedia}}"
PLUGINS_DIR="$SERVER_HOME/plugins"
WEBROOT="$SERVER_HOME/webapps/root"

# módulos Maven a compilar (cada uno produce un shaded jar en target/)
MODULES=(
  restream-plugin
  cloud-recording
  cluster-registry-plugin
  tenant-quota-plugin
  security-plugin
  ai-moderation-plugin
  hls-encryption-plugin
  spatial-360-plugin
  conference-rooms-plugin
)

green() { printf '\033[0;32m%s\033[0m\n' "$*"; }
yellow(){ printf '\033[0;33m%s\033[0m\n' "$*"; }
red()   { printf '\033[0;31m%s\033[0m\n' "$*"; }
bar()   { printf '\033[0;36m========================================\033[0m\n'; }

# ---------- comprobaciones ----------
bar; green "Keeloke TV Server - instalación de plugins"; bar
echo "SERVER_HOME : $SERVER_HOME"
echo "plugins dir : $PLUGINS_DIR"
echo

command -v java >/dev/null 2>&1 || { red "Falta Java (JDK 17+). Instálalo y reintenta."; exit 1; }

if [ "${SKIP_BUILD:-0}" != "1" ]; then
  command -v mvn >/dev/null 2>&1 || { red "Falta Maven. Instálalo (brew install maven / apt install maven) y reintenta."; exit 1; }
fi

# ---------- resolver el jar del servidor en el .m2 local (por si Maven Central no tiene la versión) ----------
resolve_server_jar() {
  local jar="${AMS_JAR:-}"
  if [ -z "$jar" ]; then
    # auto-detección en ubicaciones comunes
    for c in \
      "$SERVER_HOME/ant-media-server.jar" \
      "$SERVER_HOME/ant-media-server-service.jar" \
      "$SCRIPT_DIR/../../Keeloke-TV- Server/ant-media-server.jar"; do
      [ -f "$c" ] && { jar="$c"; break; }
    done
  fi
  if [ -n "$jar" ] && [ -f "$jar" ]; then
    yellow "Instalando el jar del servidor en el repositorio Maven local para resolver la dependencia..."
    # la versión debe coincidir con la de los pom (3.0.3)
    mvn -q install:install-file \
      -Dfile="$jar" \
      -DgroupId=io.antmedia \
      -DartifactId=ant-media-server \
      -Dversion=3.0.3 \
      -Dpackaging=jar \
      -DgeneratePom=true || yellow "  (no se pudo instalar el jar local; se intentará resolver desde Maven Central)"
  else
    yellow "No se encontró un ant-media-server.jar local; se intentará resolver la dependencia desde Maven Central."
    yellow "  (si el build falla por io.antmedia:ant-media-server:3.0.3, exporta AMS_JAR=/ruta/al/ant-media-server.jar)"
  fi
}

# ---------- compilar ----------
BUILT=()
if [ "${SKIP_BUILD:-0}" != "1" ]; then
  resolve_server_jar
  echo
  for m in "${MODULES[@]}"; do
    if [ ! -f "$m/pom.xml" ]; then
      yellow "· $m no tiene pom.xml (¿es un sidecar?), se omite del build Maven."
      continue
    fi
    green "▶ compilando $m ..."
    ( cd "$m" && mvn -q clean package -DskipTests ) || { red "  ✗ falló la compilación de $m"; exit 1; }
    BUILT+=("$m")
  done
  green "Compilación completa: ${#BUILT[@]} plugins."
else
  yellow "SKIP_BUILD=1: se omite compilación."
  BUILT=("${MODULES[@]}")
fi

# ---------- instalar ----------
echo
if [ ! -d "$SERVER_HOME" ]; then
  red "SERVER_HOME no existe: $SERVER_HOME"
  yellow "Pasa la ruta correcta:  ./install-all.sh /ruta/al/servidor"
  yellow "Los .jar quedaron compilados en cada  <plugin>/target/  para copiarlos a mano."
  exit 1
fi

mkdir -p "$PLUGINS_DIR"
INSTALLED=0
for m in "${BUILT[@]}"; do
  # copia cualquier jar shaded del target (nombre finalName: keeloke-*)
  for jar in "$m"/target/keeloke-*.jar; do
    [ -f "$jar" ] || continue
    cp -f "$jar" "$PLUGINS_DIR/"
    green "  ✔ instalado $(basename "$jar")"
    INSTALLED=$((INSTALLED+1))
  done
done

# dashboard (HTML estático) a la raíz web
if [ -f "dashboard/index.html" ]; then
  if [ -d "$WEBROOT" ]; then
    cp -f "dashboard/index.html" "$WEBROOT/keeloke-dashboard.html"
    green "  ✔ dashboard -> $WEBROOT/keeloke-dashboard.html"
  else
    yellow "  · no existe $WEBROOT ; copia dashboard/index.html a tu raíz web manualmente."
  fi
fi

# cloud-recording: sidecar standalone (no es un plugin Maven, no se auto-inicia).
# Se compila aquí y se deja listo el .service de systemd para habilitarlo a mano
# una vez configurado el bucket/credenciales.
if [ -f "cloud-recording/CloudRecordingUploader.java" ]; then
  ( cd cloud-recording && javac --release 17 -d . CloudRecordingUploader.java ) \
    && green "  ✔ cloud-recording compilado (cloud-recording/CloudRecordingUploader.class)" \
    || yellow "  ✗ no se pudo compilar cloud-recording (requiere JDK 17+ en PATH)"
  if command -v systemctl >/dev/null 2>&1 && [ -f "cloud-recording/keeloke-cloud-recording.service" ]; then
    yellow "  · unidad systemd disponible: cloud-recording/keeloke-cloud-recording.service"
    yellow "    Edita el bucket/credenciales y luego:"
    yellow "      sudo cp cloud-recording/keeloke-cloud-recording.service /etc/systemd/system/"
    yellow "      sudo systemctl daemon-reload && sudo systemctl enable --now keeloke-cloud-recording"
  fi
fi

# ---------- reinicio opcional ----------
echo
if [ "${RESTART:-0}" = "1" ]; then
  yellow "Reiniciando el servicio 'antmedia'..."
  sudo systemctl restart antmedia && green "  ✔ servicio reiniciado" || red "  ✗ no se pudo reiniciar (¿systemd? ¿permisos?)"
fi

# ---------- resumen ----------
echo
bar; green "Listo: $INSTALLED jar(s) instalados en $PLUGINS_DIR"; bar
cat <<EOF

Pasos finales:
  1) Redis: varios plugins (cluster, seguridad, cuotas, IA, DRM, salas) usan
     Redis. Asegúrate de tener uno accesible (default redis://127.0.0.1:6379).
        sudo apt install redis-server && sudo systemctl enable --now redis-server
  2) Config (opcional) en application.properties / red5.properties, por ejemplo:
        keeloke.drm.enabled=true
        keeloke.spatial.enabled=true
        keeloke.ai.redisAddress=redis://127.0.0.1:6379
     (cada plugin trae valores por defecto seguros y está DESACTIVADO hasta que
      lo habilitas / le das API key; ver los README de cada carpeta.)
  3) Reinicia el servidor si no usaste RESTART=1:
        sudo systemctl restart antmedia
  4) Dashboard:  http://TU_SERVIDOR:5080/keeloke-dashboard.html

EOF
