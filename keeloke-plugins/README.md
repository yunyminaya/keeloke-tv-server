# Keeloke TV Server - Plugins

Funcionalidad adicional construida como código abierto propio sobre Ant
Media Server Community Edition (Apache 2.0), sin usar ni desbloquear código
Enterprise. Cada plugin usa únicamente la API pública de plugins
(`io.antmedia.plugin.api`) o corre como un servicio independiente.

Compilado y verificado (`javac`) contra el classpath real de la instalación
en `Keeloke-TV- Server/` (versión 2.11.3).

## Contenido

- **`restream-plugin/`** — reenvía cada stream entrante a múltiples destinos
  RTMP (YouTube, Facebook Live, Twitch, custom) simultáneamente vía FFmpeg.
  Gestionable por REST API (`/keeloke/v1/restream/destinations`).

- **`cloud-recording/`** — sidecar Java standalone que observa las
  grabaciones `.mp4` y las sube automáticamente a almacenamiento S3-compatible
  (AWS S3, MinIO, Backblaze B2, Wasabi, etc.) apenas terminan de escribirse.

- **`cluster-registry-plugin/`** — coordinación multi-nodo: heartbeat de
  identidad, rol (origin/edge/hybrid), región, capacidad y CPU en Redis;
  registro automático del origen de cada stream; balanceo (nodo menos
  cargado), routing origin→edge (URL de pull HLS), detección de failover con
  limpieza de streams huérfanos + webhook, y señal de auto-scaling + métricas
  Prometheus para HPA/ASG/MIG externos.
  Expone `/keeloke/v1/cluster/{nodes,best-origin,origin/{id},scale,metrics}`.

- **`tenant-quota-plugin/`** — cuotas reales de streams concurrentes por
  aplicación/tenant (rechaza el publish si se excede, vía el hook
  `IStreamPublishSecurity`) y analytics de uso (minutos, streams) en Redis.
  Expone `GET /keeloke/v1/tenants/usage`. El sistema de usuarios/roles
  (ADMIN/USER/READ_ONLY) **ya existe nativo** en Community — ver
  `tenant-quota-plugin/TENANT_QUOTA_README.md`.

- **`security-plugin/`** — seguridad y analítica avanzada por tenant: tokens
  HMAC por stream, filtro IP (CIDR), TOTP (RFC 6238), geo-restricción
  (enchufable), webhooks granulares firmados, y analítica de espectadores
  (sesiones, viewers concurrentes, países). Ver `security-plugin/SECURITY_README.md`.

- **`ai-moderation-plugin/`** — moderación de contenido y detección de objetos
  en vivo, agnóstico de proveedor: captura frames con FFmpeg y los envía a la
  API de visión que elijas con tu API key (OpenAI y compatibles, Claude, o
  cualquier endpoint HTTP vía el adaptador genérico). Acción configurable al
  detectar: log, webhook y/o detener el stream. Ver `ai-moderation-plugin/AI_README.md`.

- **`dashboard/`** — panel web de una sola página (HTML autocontenido) que
  unifica tenants, uso, nodos del clúster, escalado e IA leyendo los endpoints
  REST de los plugins. Ver `dashboard/README.md`.

- **`hls-encryption-plugin/`** — DRM básico: cifrado HLS AES-128 (RFC 8216) por
  stream con entrega de clave protegida por token, claves compartidas en Redis.
  Expone `/keeloke/v1/drm/{streamId}/{key,token,status}`.

- **`spatial-360-plugin/`** — marca grabaciones como 360° inyectando metadata
  Spherical Video V1 en el MP4 al terminar el stream (sin transcode).
  Expone `/keeloke/v1/spatial/{streamId}/{mark360,unmark360,status}`.

- **`conference-rooms-plugin/`** — gestión de salas multi-participante WebRTC:
  crear/unir/salir con roles publisher/subscriber y capacidad aplicada sin
  carreras vía lock distribuido en Redis.
  Expone `/keeloke/v1/conference/rooms*`. Ver `SFU_SCALING.md` para escalar a
  grandes audiencias.

- **`SFU_SCALING.md`** — arquitectura honesta para WebRTC a gran escala: qué
  está construido (salas, edges) y qué requiere integrar un SFU open-source.

- **`CLUSTERING_AND_SCALE_ROADMAP.md`** — explicación honesta de qué falta
  para clustering completo, WebRTC a gran escala y DRM, y por qué esas tres
  cosas concretas no se pueden fingir con un plugin (requieren cambios en el
  núcleo del pipeline de medios, o en el caso de DRM, una cuenta con un
  proveedor de licencias certificado).

## Instalar un plugin (restream / cluster-registry)

Cada plugin es un módulo Maven independiente que depende del artefacto
público `io.antmedia:ant-media-server` (publicado en Maven Central). Para
compilarlo:

```bash
cd keeloke-plugins/restream-plugin
mvn clean package
cp target/keeloke-restream-plugin.jar /path/to/Keeloke-TV-Server/plugins/
# reiniciar el servidor
```

(Si no tienes Maven instalado, cualquier IDE Java o `javac` con el
classpath del servidor instalado también compila el código - así se validó
en este entorno.)

## Correr el uploader de grabaciones

```bash
cd keeloke-plugins/cloud-recording
./run.sh /path/to/Keeloke-TV-Server/webapps mi-bucket-de-grabaciones \
  --endpoint-url=https://s3.us-west-000.backblazeb2.com \
  --delete-after-upload
```
