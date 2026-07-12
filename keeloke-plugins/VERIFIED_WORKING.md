# Verificación en vivo — 2026-07-11

Esto documenta una prueba real, de extremo a extremo, del servidor instalado y
sus 3 plugins de servidor (restream, cluster-registry, tenant-quota). No es
una suposición: se arrancó el servidor, se publicó un stream real por RTMP
con FFmpeg, y se consultaron los endpoints REST en vivo.

## Bugs reales encontrados y corregidos

1. **`start.sh` no soporta rutas con espacios.** La instalación vive en
   `.../Keeloke TV/Keeloke TV/Keeloke-TV- Server` (con espacios), lo que
   rompe el script internamente (`source`, `sed -i`, `ls` sin comillas).
   Workaround aplicado: symlink sin espacios
   (`~/keeloke-tv-server-run -> ".../Keeloke-TV- Server"`) y ejecutar desde
   ahí. `start.sh` en sí no se modificó para esto (es del vendor); considerar
   mover la instalación a una ruta sin espacios permanentemente.

2. **`start.sh` requiere `/var/log/antmedia` con sudo.** Se parcheó
   `start.sh` (ver diff) para caer a `${RED5_HOME}/log-data` si no hay
   permisos, en vez de abortar exigiendo `sudo`.

3. **`conf/logback.xml` tenía `/var/log/antmedia` hardcodeado**,
   independiente del symlink de start.sh. Se redirigió a
   `log-data/` local.

4. **El jar instalado (2.11.3) requiere JDK 17** (class file version 61),
   pero solo había JDK 21 en el sistema, y sus flags de JVM
   (`-XX:+UseBiasedLocking`) fueron removidos en JDK 18+, causando un crash
   inmediato de la JVM. Se instaló `openjdk@17` vía Homebrew (fórmula, no
   cask, para evitar el instalador con sudo) y se exportó
   `JAVA_HOME`/`PATH` antes de `start.sh`.

5. **Los plugins compilados con `javac` de JDK 21 sin `--release` generaban
   bytecode versión 65 (Java 21)**, que la JVM 17 en ejecución rechaza
   ("problem with class file"). Se recompiló todo con `--release 17`.

6. **Los plugins no se cargaban en absoluto al inicio.** Causa: cada app
   (`LiveApp`, `WebRTCApp`, `live`) tiene su propio `red5-web.xml` con
   `<context:component-scan base-package="io.antmedia.plugin" />` — que NO
   incluye `tv.keeloke.plugins`. Se agregó una línea de component-scan
   adicional para `tv.keeloke.plugins` en las 3 apps.

7. **`TenantQuotaPlugin` (que implementa `IStreamPublishSecurity`) no estaba
   en la lista de gates de publish.** `streamPublishSecurityList` es una
   lista Spring XML explícita, no auto-detectada. Se agregó
   `<ref bean="tenantQuotaPlugin"/>` a esa lista en las 3 apps.

8. **Los `@RestController`/`@RequestMapping` (Spring MVC) de los 3 plugins
   devolvían 404 en todas las rutas.** Causa: Ant Media Server usa
   JAX-RS/Jersey (mapeado en `/rest/*`, escaneando `io.antmedia.rest`), no
   Spring MVC — no hay `DispatcherServlet` registrado. Se reescribieron los
   3 REST services con anotaciones JAX-RS (`@Path`, `@GET`, etc.) y se
   agregó `tv.keeloke.plugins` al `jersey.config.server.provider.packages`
   en `web.xml` de las 3 apps.

9. **Aun con JAX-RS, los endpoints devolvían 500 (`NullPointerException`)
   en los campos `@Autowired`.** Causa: Jersey instancia sus propios objetos
   resource, no los toma del contenedor de Spring. El patrón real usado por
   `io.antmedia.rest.RestServiceBase` (confirmado con `javap`) es
   `@Context ServletContext` + `WebApplicationContextUtils
   .getWebApplicationContext(servletContext).getBean(...)`. Se aplicó el
   mismo patrón en los 3 REST services.

## Lo que quedó confirmado funcionando de verdad

- Arranque limpio del servidor (JDK 17, Redis corriendo).
- Los 3 plugins cargan e inicializan en las 3 apps (`live`, `WebRTCApp`,
  `LiveApp`) — confirmado en logs.
- Publish RTMP real con FFmpeg (`testsrc` + tono senoidal) ingerido
  correctamente (HLS muxer procesando frames, key frame recibido).
- `GET /LiveApp/rest/keeloke/v1/cluster/nodes` responde JSON real con los 3
  nodos (uno por app) y sus heartbeats.
- El conteo de streams activos del `cluster-registry-plugin` sube a 1 al
  publicar y baja a 0 al detener el stream — confirmado en vivo vía REST,
  no solo en logs.
- `GET /LiveApp/rest/keeloke/v1/tenants/usage` y
  `GET /LiveApp/rest/keeloke/v1/restream/destinations` responden `[]`
  (JSON válido, sin error) cuando no hay datos.

## Lo que NO se verificó todavía (honesto)

- El **rechazo real de un publish por cuota excedida** en
  `tenant-quota-plugin` (`isPublishAllowed` devolviendo `false`) — se
  confirmó que el bean está registrado en `streamPublishSecurityList`, pero
  no se forzó el escenario de superar la cuota con streams reales
  concurrentes.
- El **restream real a un destino RTMP externo** (YouTube/Facebook) - se
  verificó que el endpoint REST de destinos responde, pero no se probó con
  una URL RTMP externa real (requeriría credenciales de una cuenta real).
- La **subida a S3 de `cloud-recording`** - no se ejecutó contra un bucket
  real en esta sesión.
- Comportamiento bajo carga, reinicio abrupto, o múltiples nodos reales
  (todo se probó en un solo nodo local).

## Cómo reproducir

```bash
# 1. Redis
redis-server --port 6379 &

# 2. JDK 17
brew install openjdk@17

# 3. Symlink sin espacios (si tu ruta de instalación tiene espacios)
ln -sfn "/ruta/con espacios/Keeloke-TV- Server" ~/keeloke-tv-server-run

# 4. Arrancar
cd ~/keeloke-tv-server-run
JAVA_HOME=/usr/local/opt/openjdk@17 PATH="/usr/local/opt/openjdk@17/bin:$PATH" ./start.sh

# 5. Publicar un stream de prueba
ffmpeg -re -f lavfi -i "testsrc=size=640x480:rate=30" -f lavfi -i "sine=frequency=1000" \
  -c:v libx264 -preset ultrafast -tune zerolatency -c:a aac -b:a 128k \
  -f flv "rtmp://127.0.0.1:1935/LiveApp/mi-stream-de-prueba"

# 6. Verificar
curl http://127.0.0.1:5080/LiveApp/rest/keeloke/v1/cluster/nodes
```
