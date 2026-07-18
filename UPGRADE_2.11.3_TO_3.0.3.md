# Actualización 2.11.3 → 3.0.3 — verificada en vivo (2026-07-11)

Este documento registra la actualización real de la instalación local de
Keeloke TV Server de Keeloke TV Server Community 2.11.3 a 3.0.3, incluyendo
migración de los 3 plugins propios y todos los problemas reales encontrados.

## Resumen

- Respaldo completo de la instalación 2.11.3 antes de tocar nada
  (`Keeloke-TV-Server-2.11.3-backup-<fecha>`).
- Jars, `lib/`, `webapps/`, y `plugins/` base reemplazados por los de 3.0.3.
- Branding Keeloke TV reaplicado sobre los nuevos `webapps/root` y
  `StreamApp-3.0.3.war`.
- Los 3 plugins (`restream-plugin`, `cluster-registry-plugin`,
  `tenant-quota-plugin`) migrados a la nueva API de plugins y recompilados.
- Parches de `web.xml`/`red5-web.xml` reaplicados en las 3 apps.
- **Verificado con un publish RTMP real de FFmpeg**: ingest funcionando,
  key frame recibido, `activeStreamCount` subiendo/bajando en tiempo real
  vía el endpoint REST — igual que se verificó para 2.11.3.

## Cambios de API entre 2.11.3 y 3.0.3 (confirmados con `javap`)

`io.antmedia.plugin.api.IStreamListener`:

```diff
- public abstract void streamStarted(java.lang.String);
- public abstract void streamFinished(java.lang.String);
+ public default void streamStarted(io.antmedia.datastore.db.types.Broadcast);
+ public default void streamFinished(io.antmedia.datastore.db.types.Broadcast);
```

`org.red5.server.api.stream.IStreamPublishSecurity`:

```diff
- boolean isPublishAllowed(IScope, String, String, Map<String,String>, String);
+ boolean isPublishAllowed(IScope, String, String, Map<String,String>, String, String, String, String);
```

(Los 3 parámetros `String` nuevos no tienen semántica documentada en la
superficie pública que pudimos inspeccionar con `javap` — se dejaron sin
usar en `TenantQuotaPlugin`, solo para satisfacer la firma.)

## Bug real encontrado y corregido durante la migración

**`TenantQuotaPlugin` nunca incrementaba `activeStreamsPerTenant` en la
práctica** (contra 2.11.3): el método `recordTenantStreamStart()` existía
pero nada lo invocaba desde `streamStarted()`. Es decir, la cuota nunca
podía rechazar nada porque el contador de streams activos siempre marcaba
0. Se corrigió incrementando directamente dentro de `isPublishAllowed()`
(que sí conoce el tenant vía `IScope.getName()`) y decrementando en
`streamFinished()` usando el nombre de app de esa misma instancia del
plugin (una instancia por app/tenant). Ver el código para el detalle.

## Bloqueador de entorno encontrado: macOS Gatekeeper rechazaba FFmpeg nativo

Al publicar el primer stream de prueba en 3.0.3, el ingest de video fallaba
con `NoClassDefFoundError: Could not initialize class
org.bytedeco.ffmpeg.global.avcodec`, causado por un
`UnsatisfiedLinkError`/`Operation not permitted` al cargar las librerías
nativas `.dylib` de FFmpeg 7.1 (traídas por JavaCPP 1.5.11, la versión que
usa 3.0.3).

Diagnóstico: `codesign -dv` mostraba `code object is not signed at all` y
`spctl -a -t exec` las rechazaba (`rejected: no usable signature`) — macOS
Gatekeeper bloqueaba la carga de estas librerías nativas sin firmar,
recién extraídas del jar por primera vez en esta máquina. (Las librerías de
la versión anterior, FFmpeg 5.1.2 vía JavaCPP 1.5.8, ya habían sido
extraídas y aprobadas en sesiones previas, por eso 2.11.3 nunca mostró este
problema.)

**Con autorización explícita del usuario**, se firmaron localmente (ad-hoc)
las 24 librerías `.dylib` en el caché de JavaCPP
(`~/.javacpp/cache/.../*.dylib`) con `codesign --force -s -`, y se
reinició el servidor (una JVM que falla al inicializar una clase la marca
como permanentemente rota hasta el próximo reinicio — un solo intento
fallido no se puede "reintentar" en caliente). Tras el reinicio, el ingest
de video funcionó de inmediato.

Esta firma ad-hoc es local a esta máquina y no se sube al repositorio (no
son archivos del proyecto, viven en `~/.javacpp/cache`). Cualquier otra
instalación de Keeloke TV Server en macOS que use FFmpeg 7.1/JavaCPP 1.5.11
por primera vez puede toparse con el mismo bloqueo de Gatekeeper y
necesitará el mismo `codesign --force -s -` sobre los `.dylib` extraídos.

## Estado final verificado

- `Implementation-Version: 3.0.3` confirmado en el jar instalado.
- Los 9 mensajes de inicialización de plugins (3 plugins × 3 apps)
  aparecen en el log de arranque.
- Publish RTMP real de FFmpeg ingerido correctamente (HLS muxer, key frame
  recibido).
- `GET /LiveApp/rest/keeloke/v1/cluster/nodes` responde con
  `activeStreamCount` subiendo a 1 y bajando a 0 en sincronía con el
  publish/stop real del stream.
