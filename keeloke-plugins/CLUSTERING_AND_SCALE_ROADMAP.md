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
| Registro de nodos de clúster | `cluster-registry-plugin/` | Cada nodo hace heartbeat de su identidad y carga (streams activos) en Redis. Expone `GET /keeloke/v1/cluster/nodes`. |

## Lo que NO se construyó, y por qué

### 1. Clustering completo (multi-nodo con failover y enrutamiento origin/edge)

El registro de nodos de arriba es **descubrimiento de servicio**, no
clustering. Clustering real de un servidor de medios requiere, como mínimo:

- **Enrutamiento origin/edge**: cuando un espectador llega a un nodo "edge"
  que no tiene el stream, ese nodo debe poder *pull*-earlo desde el nodo
  "origin" en tiempo real, sin interrumpir a otros espectadores.
- **Migración/failover de streams**: si el nodo origin muere, otro nodo debe
  poder tomar el stream (si el publisher puede reconectar) o al menos
  notificar a los edges limpiamente.
- **Estado de sesión compartido** (viewers, tokens, ABR ladders) entre nodos,
  no solo el conteo de streams.
- Pruebas de carga y de particionamiento de red — clustering roto bajo
  partición de red es peor que no tener clustering.

Esto vive dentro del pipeline de medios del servidor (`RtmpMuxer`,
`WebRTCAdaptor`, el motor de scope de Red5), no se puede añadir de forma
segura solo con un plugin externo. Es semanas de trabajo de un equipo que
conozca a fondo esa base de código, con pruebas de carga reales — no algo
razonable de fingir con código no probado.

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
