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

### 3. DRM (Widevine / FairPlay / PlayReady)

DRM real requiere:

- Un **servidor de licencias** (Widevine/FairPlay license server), que
  típicamente es un servicio de un proveedor certificado (Axinom, EZDRM,
  BuyDRM, etc.) — Google/Apple no dan las claves de encriptación de
  contenido a cualquiera, hay un proceso de certificación.
- Empaquetado de contenido cifrado (CENC) integrado en el pipeline HLS/DASH.
- Cumplimiento de "robustness requirements" de cada esquema DRM.

**No es técnicamente posible construir DRM funcional sin una cuenta con un
proveedor de licencias DRM certificado.** No es una limitación de tiempo de
ingeniería, es un requisito estructural del ecosistema DRM. Si en algún
momento tienes cuenta con un proveedor de licencias, ahí sí puedo ayudar a
integrar el empaquetado CENC + el flujo de licencias en el servidor.

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
