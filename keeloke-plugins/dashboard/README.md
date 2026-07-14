# Keeloke TV Server — Panel de control (dashboard)

Panel web de una sola página, sin dependencias externas, que unifica en una
vista todo lo que exponen los plugins de Keeloke:

- **Tenants & uso**: streams activos vs cuota por tenant (app), minutos totales,
  streams totales, y edición de cuota en línea (`tenant-quota-plugin`).
- **Nodos del clúster**: host, rol (origin/edge/hybrid), región, streams, CPU y
  barra de carga (`cluster-registry-plugin`).
- **Escalado automático**: recomendación SCALE_UP/DOWN/HOLD, carga media, nodos
  deseados y el endpoint de métricas Prometheus para tu HPA/ASG/MIG.
- **IA moderación & detección**: proveedor activo, modelo, umbral, muestreo,
  acción al detectar y si la API key está configurada (`ai-moderation-plugin`).

Todo se lee en el navegador desde los endpoints `/<app>/rest/keeloke/v1/*`, así
que no hay acoplamiento entre plugins ni servidor intermedio.

## Cómo servirlo

El archivo `index.html` es autocontenido (HTML + CSS + JS en un solo fichero).
Dos formas de publicarlo:

1. **Dentro del propio servidor** (recomendado): copia `index.html` a la raíz
   web de una aplicación, por ejemplo:

   ```bash
   sudo cp keeloke-plugins/dashboard/index.html \
     /usr/local/antmedia/webapps/root/keeloke-dashboard.html
   ```

   y ábrelo en `http://TU_SERVIDOR:5080/keeloke-dashboard.html`.
   Como se sirve desde el mismo origen, el campo "URL base" se autocompleta y no
   hay problemas de CORS.

2. **Desde cualquier hosting estático / tu máquina**: abre el archivo y escribe
   la URL base (`http://TU_SERVIDOR:5080`) y la app (`LiveApp`) arriba. Necesitarás
   que el servidor permita CORS para ese origen, o usar la opción 1.

## Uso

- **URL base**: la raíz de tu servidor Keeloke (ej. `http://203.0.113.10:5080`).
- **App / tenant**: la aplicación cuyo REST se consulta para endpoints con scope
  (por defecto `LiveApp`). Los endpoints de cluster/IA son globales pero se
  invocan bajo esa app igualmente.
- **Auto**: refresco cada 5 s.
- La edición de cuota hace `POST /keeloke/v1/tenants/{app}/quota`.

## Nota sobre "multi-tenant"

En este modelo cada **aplicación** del servidor es un **tenant**: ya tiene su
propio nombre, sus streams, sus usuarios y (con `security-plugin`) su propia
configuración de tokens/IP/geo/webhooks, más su cuota y medición de uso con
`tenant-quota-plugin`. Crear un tenant nuevo = crear una app nueva (función
nativa del servidor) y asignarle una cuota desde este panel. El aislamiento
fuerte de datos entre apps lo da el propio servidor; los plugins añaden cuotas,
seguridad y analítica por tenant encima.
