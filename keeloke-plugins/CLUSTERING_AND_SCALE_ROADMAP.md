# Roadmap honesto: clustering, WebRTC a gran escala y DRM

Este documento existe para ser transparente sobre qué tan lejos llegan los
plugins de `keeloke-plugins/` frente a lo que realmente ofrece la edición
Enterprise de Ant Media, y qué haría falta para cerrar esa brecha con
ingeniería propia (sin licencia, sin código Enterprise copiado).

## Lo que SÍ se construyó (funcional, en este repo)

| Función | Dónde | Qué hace realmente |
|---|---|---|
| Restream a redes sociales | `restream-plugin/` | Reenvía cada stream entrante a N destinos RTMP (YouTube, Facebook, Twitch, etc.) vía FFmpeg stream-copy. Gestionable por REST API. |
| Grabación en la nube | `cloud-recording/` | Sidecar que observa las grabaciones `.mp4` y las sube a S3/MinIO/Backblaze/Wasabi apenas terminan de escribirse. |
| Coordinación de clúster | `cluster-registry-plugin/` | Heartbeat de identidad, rol (origin/edge/hybrid), región, capacidad y CPU en Redis; registro automático de qué nodo es el origin de cada stream; balanceo (nodo menos cargado), routing origin→edge (URL de pull HLS), detección de nodos muertos con limpieza de streams huérfanos + webhook de failover, y señal de auto-scaling + métricas Prometheus para un autoscaler externo. |
| Seguridad y analítica | `security-plugin/` | Tokens HMAC por stream, filtro IP CIDR, TOTP (RFC 6238), geo-restricción (enforcement listo, resolución IP→país enchufable), webhooks granulares firmados, analítica de espectadores por stream. Lógica pura verificada con 24 pruebas unitarias. |

## Lo que SÍ se cubrió parcialmente (con límites honestos)

### 1. Clustering: coordinación construida; migración transparente NO

`cluster-registry-plugin/` ahora hace **coordinación real**, no solo
descubrimiento:

- ✅ **Enrutamiento origin/edge**: `ClusterLoadBalancer` resuelve el nodo origin
  de un stream y produce la URL de pull HLS que un edge usa. `GET /keeloke/v1/cluster/origin/{streamId}`.
- ✅ **Balanceo de publish**: selecciona el nodo menos cargado que puede aceptar
  (origin/hybrid, bajo capacidad, prefiriendo región). `GET /keeloke/v1/cluster/best-origin`.
- ✅ **Detección de failover**: `ClusterFailoverMonitor` detecta nodos con
  heartbeat expirado, limpia sus streams huérfanos del registro y dispara un
  webhook con el nodo de reemplazo recomendado (lock distribuido para que solo
  un nodo haga la limpieza).
- ✅ **Señal de auto-scaling**: `ClusterAutoScaleAdvisor` calcula carga promedio y
  emite recomendación SCALE_UP/DOWN/HOLD + métricas Prometheus. `GET /keeloke/v1/cluster/metrics`.

**Los límites honestos** (esto NO lo hace, y por qué):

- **Migración transparente de un ingest en vivo**: si el nodo origin muere, su
  sesión RTMP/WebRTC entrante muere con él — ningún servidor puede resucitar la
  conexión inbound de otro. El monitor *notifica* el evento y el reemplazo; el
  publisher debe reconectar (a donde `best-origin` lo mande). Eso es lo máximo
  correcto sin fingir migración mágica.
- **El servidor NO lanza instancias cloud**: expone la señal de carga; un HPA de
  K8s / ASG de AWS / MIG de GCP hace la actuación con sus propios permisos. Darle
  credenciales cloud al media server para crear/destruir capacidad es un riesgo
  de seguridad enorme — por eso el límite está donde está.
- **Estado de sesión compartido** (ladders ABR, tokens de viewer entre nodos):
  el `security-plugin` ya comparte config y tokens vía Redis; el estado interno
  del pipeline de medios (buffers, ABR) sigue siendo por-nodo.

### 2. WebRTC a gran escala (miles de espectadores concurrentes)

Ant Media Community ya soporta WebRTC funcional, pero para miles de
espectadores simultáneos por stream se necesita una arquitectura SFU
(Selective Forwarding Unit) con:

- Simulcast/SVC real (múltiples calidades codificadas simultáneamente).
- Enrutamiento eficiente de paquetes RTP entre muchos peers sin
  re-codificar.
- Balanceo de carga de CPU/red entre múltiples instancias del SFU.

Esto es, otra vez, trabajo de meses en la capa de transporte de medios, no
algo que un plugin externo pueda añadir con seguridad.

### 3. DRM básico (AES-128 HLS) — ✅ CONSTRUIDO · vs DRM certificado (Widevine/FairPlay) — límite estructural

**DRM básico (cifrado AES-128 HLS)**: ✅ hecho en `hls-encryption-plugin/`.
Cada segmento `.ts` se cifra con AES-128-CBC (estándar RFC 8216) y la clave se
entrega por un endpoint protegido con token. Detiene el ripeo casual y la
reproducción no autorizada. 15 aserciones unitarias verifican clave/IV/keyinfo/token.

**DRM certificado (Widevine / FairPlay / PlayReady)**: esto sí es un límite
estructural, no de tiempo. Requiere:

- Un **servidor de licencias** de un proveedor certificado (Axinom, EZDRM,
  BuyDRM, etc.) — Google/Apple no dan las claves de contenido sin un proceso
  de certificación.
- Empaquetado CENC integrado en el pipeline HLS/DASH.
- Cumplimiento de "robustness requirements" de cada esquema.

**No es posible construir DRM certificado sin una cuenta con un proveedor de
licencias.** Si en algún momento la tienes, ahí sí puedo integrar el
empaquetado CENC + el flujo de licencias encima del cifrado que ya existe.

### 4. WebRTC a gran escala / SFU — ver `SFU_SCALING.md`

Gestión de salas multi-participante (roles, capacidad, cluster-wide): ✅ hecho
en `conference-rooms-plugin/`. Fan-out WebRTC a miles por stream: se documenta
la arquitectura real (edges del cluster-plugin + integración con un SFU
open-source como mediasoup/Janus/Pion/LiveKit), sin fingir un SFU propio.

### 5. 360° — ✅ CONSTRUIDO

`spatial-360-plugin/` inyecta metadata Spherical Video V1 en las grabaciones
MP4 para que los reproductores las muestren en esfera. Sin transcode (los
píxeles equirectangulares pasan intactos). 13 aserciones verifican la
construcción del box y la reescritura de tamaños MP4.

## Próximos pasos sugeridos, en orden de esfuerzo/valor

1. Probar `restream-plugin` y `cloud-recording` en un entorno real (ya
   compilan limpio contra el jar 2.11.3 instalado).
2. Si necesitas más de un nodo, levantar Redis y correr
   `cluster-registry-plugin` en cada nodo — te da visibilidad de carga, base
   para un balanceador externo (nginx/HAProxy con lógica de "menos streams
   activos primero" usando `/keeloke/v1/cluster/nodes`).
3. Si el negocio realmente necesita clustering origin/edge o SFU a escala,
   evaluar seriamente si conviene comprar la licencia Enterprise (que ya
   resuelve esto, probado en producción por miles de despliegues) en lugar
   de reconstruirlo desde cero.
