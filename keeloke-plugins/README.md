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

- **`cluster-registry-plugin/`** — heartbeat de identidad y carga de cada
  nodo en Redis (usa Redisson, ya incluido en el servidor). Base para
  balanceo de carga externo entre múltiples nodos.
  Expone `GET /keeloke/v1/cluster/nodes`.

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
