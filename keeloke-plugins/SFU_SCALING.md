# WebRTC a gran escala (SFU) — arquitectura honesta

Este documento explica **qué** entrega Keeloke para WebRTC multi-participante y
a gran escala, y **qué** requiere infraestructura adicional. Sin humo: no hay un
"SFU propio de miles de espectadores" escrito en un plugin, porque eso es un
plano de medios completo, no algo que se pueda añadir de forma segura y
verificable como plugin externo.

## Lo que SÍ está construido (real y verificado)

### Gestión de salas multi-participante — `conference-rooms-plugin/`
El estado de señalización/coordinación de una conferencia many-to-many:

- Crear/borrar salas, listar, consultar.
- Unirse/salir con roles **PUBLISHER / SUBSCRIBER**.
- Capacidad por sala (máx. publishers y máx. participantes) aplicada
  **sin condiciones de carrera** mediante lock distribuido en Redis (dos joins
  simultáneos no pueden exceder el cupo).
- Estado compartido en todo el clúster (participantes en nodos distintos ven la
  misma sala).
- 13 aserciones unitarias verifican la lógica de capacidad, roles y join/leave.

Esto es exactamente la capa que una app de videoconferencia necesita del
servidor: descubrir quién está en la sala y a qué stream suscribirse. **El
reenvío de medios** (la pista WebRTC de cada participante distribuida a los
demás) lo hace el motor WebRTC del servidor.

## Cómo se escala a grandes audiencias (las dos rutas reales)

### Ruta A — Origin/Edge con `cluster-registry-plugin` (sin dependencias extra)
Para difusión (1 publisher → muchos espectadores), la vía de escala horizontal
ya está: el publisher ingesta en un nodo **origin**; los espectadores se
reparten entre nodos **edge** que hacen *pull* del origin (el
`cluster-registry-plugin` resuelve el origin y da la URL de pull, y el balanceo
reparte la carga). Cada edge sirve su porción de espectadores; añades edges y
escalas. El `ClusterAutoScaleAdvisor` emite la señal para que tu HPA/ASG/MIG
añada edges automáticamente bajo carga.

Límite honesto: esto escala **espectadores** de un broadcast. Para WebRTC puro a
miles por stream con latencia sub-segundo, el fan-out WebRTC de cada edge lo
hace el motor del servidor; a escalas muy grandes se combina con la Ruta B.

### Ruta B — SFU open-source dedicado (para conferencias grandes / miles WebRTC)
Un SFU (Selective Forwarding Unit) recibe la pista de cada publisher una vez y
la reenvía selectivamente a cada suscriptor, con simulcast y capas de calidad.
Construir uno de producción desde cero es meses de trabajo de un equipo
especializado; lo correcto es integrar uno probado:

- **mediasoup** (Node/C++), **Janus** (C), **Pion** (Go) o **LiveKit** (Go).

Keeloke encaja con cualquiera de ellos así:
1. `conference-rooms-plugin` mantiene el estado de sala/roles/capacidad
   (señalización) — ya hecho.
2. El SFU externo hace el reenvío de medios.
3. El `security-plugin` (tokens por stream) protege el acceso a las pistas.
4. El `cluster-registry-plugin` reparte instancias del SFU por carga.

La integración concreta con el SFU elegido (SDP/ICE bridging, asignación de
salas a instancias) es trabajo de despliegue que **debe probarse con carga
real** — por eso se documenta como arquitectura y no se finge como código
"terminado". Sería deshonesto marcar "SFU a miles" como hecho sin esa prueba.

## Resumen

| Necesidad | Estado |
|---|---|
| Salas multi-participante (señalización, roles, capacidad, cluster-wide) | ✅ Hecho y verificado (`conference-rooms-plugin`) |
| Difusión a gran audiencia (1→muchos) por edges | ✅ Vía `cluster-registry-plugin` (origin/edge + autoscale) |
| Fan-out WebRTC puro a miles/stream sub-segundo | ⚙️ Requiere SFU open-source integrado (arquitectura documentada arriba) |
| Simulcast / capas adaptativas del lado cliente | ⚙️ Lo aporta el SFU/motor WebRTC, se configura en el cliente |
