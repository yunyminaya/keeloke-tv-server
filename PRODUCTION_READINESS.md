# Keeloke TV Server — Checklist de producción

Estado al 2026-07-19. Este documento resume qué se arregló, qué se probó
en vivo, y qué falta antes de apuntar tráfico real de producción a un
servidor con este código.

## Hecho y verificado en vivo (no solo compilación)

- **`security-plugin` realmente activo**: `StreamAccessGuard` y
  `ViewerAnalyticsPlugin` estaban compilados pero nunca wireados en las
  listas de gates de Ant Media (`streamPublishSecurityList` /
  `streamPlaySecurityList`). Corregido en las 3 apps
  (`deployment-config-patches/{LiveApp,WebRTCApp,live}/red5-web.xml`).
  Verificado con publish real: sin token → rechazado; con token válido →
  aceptado.
- **Contador de streams del cluster nunca va a negativo**
  (`cluster-registry-plugin`): `streamFinished` sin `streamStarted`
  pareado (ej. publish rechazado) hacía que `activeStreamCount` se fuera a
  negativo permanentemente. Ahora tiene piso en 0.
- **`conference-rooms-plugin` no filtra stack traces internos**: crear una
  sala sin `roomId` tiraba un `NullPointerException` de Redisson como 500
  crudo. Ahora valida y responde 400 limpio.
- **Build roto de 2 plugins arreglado**: `restream-plugin` y
  `tenant-quota-plugin` seguían apuntando a `ant-media-server:2.11.3` (la
  versión pre-migración) mientras los otros 7 plugins ya usaban `3.0.3` -
  por eso fallaban al compilar. Alineados a 3.0.3 + Java 17, igual que el
  resto.
- **Fallas de disponibilidad bajo carga concurrente arregladas
  (fail-open)**: con 8+ streams publicando/reproduciendo al mismo tiempo,
  ~30% fallaba con `Server error` porque `SecurityConfigStore.get()` y
  `ViewerAnalyticsPlugin.isPlayAllowed()` hacían llamadas síncronas a
  Redis sin manejo de excepciones - un hilo interrumpido bajo contención
  tumbaba el publish/play entero. Ahora ambos capturan la excepción y
  degradan a un valor por defecto seguro (permisivo) en vez de abortar la
  conexión. **Ver "Pendiente" abajo - esto es una mitigación, no la
  causa raíz.**
- **Mensajería falsa de licencia/Enterprise quitada del panel**: el popup
  "Invalid License", el popup "Your license expires in N days", el banner
  "Get Enterprise Edition for Ultra Low Latency Streaming" y los 8 avisos
  por función en Settings. El panel ahora se reporta honestamente como
  Community Edition. Ver `branding/strip-enterprise-banner.sh`
  (reaplicable tras una reinstalación desde cero, ya que ese bundle no es
  parte del código fuente Java de este repo).
- **Persistencia de Redis**: por defecto corría sin AOF (hasta ~1h de
  pérdida de datos ante un crash). Ver
  `deployment-config-patches/redis/redis-production.conf` con AOF +
  `maxmemory-policy noeviction` (Redis se usa como base de datos real
  aquí - cluster/seguridad/cuotas/salas - no como caché descartable).
- **Respaldo**: `deployment-config-patches/backup.sh` respalda las bases
  MapDB (`*.db`) + el snapshot/AOF de Redis, comprime, y aplica retención
  de 14 días. Pensado para cron. Probado contra la instancia real.
- **`cloud-recording` integrado al flujo de instalación**: antes había
  que compilarlo y correrlo a mano; `install-all.sh` ahora lo compila y
  deja lista una unidad systemd
  (`keeloke-plugins/cloud-recording/keeloke-cloud-recording.service`)
  para habilitar con `systemctl enable` una vez configurado el bucket.

## Pendiente antes de tráfico real de producción

1. **[Importante] Consolidar las conexiones Redis.** Cada plugin que usa
   Redis (`SecurityConfigStore`, `ViewerAnalyticsPlugin`, `ClusterRedis`,
   `TenantQuotaPlugin`, `RoomManager`, `AiConfigStore`, `HlsKeyStore`)
   crea su **propio** `RedissonClient` con pool de 24 conexiones, y esto
   se repite en cada una de las 3 apps (LiveApp/WebRTCApp/live) por
   separado - hasta ~500 conexiones TCP redundantes a un solo Redis. Esto
   es la causa raíz real de los `RedisException: InterruptedException`
   bajo carga (ver punto arriba); el fail-open evita que tumben streams,
   pero el desperdicio de recursos y la contención de fondo siguen
   ahí. Arreglo correcto: un solo `RedissonClient` compartido por app
   (o por JVM, vía un holder estático), inyectado a los 7 componentes en
   vez de que cada uno llame a `Redisson.create()` en su propio
   `@PostConstruct`. Toca 7 archivos - no se hizo en esta sesión por
   alcance/riesgo, decisión explícita de posponerlo.
2. **Contraseña de Redis.** Ahora mismo Redis corre sin `requirepass`
   (solo mitigado por estar en `bind 127.0.0.1`). Usa
   `redis-production.conf` en el servidor real y actualiza
   `keeloke.*.redisAddress` en cada plugin a
   `redis://:LA_CONTRASEÑA@127.0.0.1:6379`.
3. **Nunca probado en Linux real.** Todo lo de esta sesión se validó en
   macOS (dev). El workaround de WebRTC (JDK17 x86_64 vía Rosetta) es
   específico de este Mac - en un servidor Linux x86_64/arm64 real
   probablemente no aplique, pero hay que confirmarlo con un despliegue
   real antes de asumir que "funciona igual".
4. **`restream-plugin` / `tenant-quota-plugin`: dependencias no
   resolubles desde Maven Central público** (`ONVIF`, `mina-core`
   exactas que pedía el pom viejo). Se arregló migrando a la misma
   versión 3.0.3 que ya resuelve bien localmente, pero si en el futuro se
   hace un build limpio sin el `.m2` local ya poblado, revisar que
   `io.antmedia:ant-media-server:3.0.3` tenga un POM real instalado (no
   uno sintético vacío) antes de compilar - ver histórico de este mismo
   problema en el log de commits.
5. **HTTPS/SSL** no configurado ni probado (`enable_ssl.sh` existe en el
   servidor pero requiere un dominio real apuntando al servidor).
6. **Sin prueba de carga a escala real** - se validó hasta 10 streams
   concurrentes en esta Mac (10 núcleos). No representa la capacidad de
   un servidor de producción real ni concurrencia de cientos/miles de
   viewers.
7. **Credenciales de admin del panel** sin configurar (primer login
   pendiente - es un paso manual intencional de Ant Media, no se puede
   automatizar sin exponer una contraseña).
8. **Monitoreo/alertas** no configurados (hay métricas Prometheus
   expuestas por `cluster-registry-plugin`, pero nada las está
   scrapeando/alertando todavía).

## Cómo desplegar cuando haya un servidor real

```bash
# 1. En el servidor Linux, instala el server base (ver install_ant-media-server.sh
#    o despliega el ant-media-server.jar + webapps de este repo).

# 2. Redis con persistencia:
sudo apt install redis-server
sudo cp deployment-config-patches/redis/redis-production.conf /etc/redis/redis.conf
#   edita la contraseña real primero
sudo systemctl restart redis-server

# 3. Plugins Keeloke:
cd keeloke-plugins
./install-all.sh /ruta/al/servidor
#   SKIP_BUILD=1 si ya compilaste antes y solo quieres reinstalar los .jar

# 4. Debranding del panel (banner/popups de licencia Ant Media):
./branding/strip-enterprise-banner.sh /ruta/al/servidor

# 5. Respaldos por cron (cada 6h, ejemplo):
#    0 */6 * * * /ruta/al/servidor/deployment-config-patches/backup.sh

# 6. Reinicia y verifica:
sudo systemctl restart antmedia
curl http://localhost:5080/LiveApp/rest/keeloke/v1/cluster/nodes
```
