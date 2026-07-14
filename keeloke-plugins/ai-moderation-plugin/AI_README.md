# Keeloke TV Server — Plugin de IA (moderación & detección)

Moderación de contenido y detección de objetos en streams en vivo, **agnóstico
de proveedor**: apunta el servidor a **cualquier API de visión del mercado** con
tu propia API key y sin tocar código.

## Cómo funciona

1. Cuando un stream empieza, el plugin captura un frame cada N segundos con
   FFmpeg (ya incluido en el servidor) — sin escribir a disco, el JPEG va por
   stdout.
2. Envía ese frame al proveedor de IA que configuraste.
3. El proveedor devuelve categorías (sexual, violencia, armas, drogas, odio…) y
   etiquetas de objetos detectados. Se normaliza todo a un `ModerationResult`.
4. Si alguna categoría supera el umbral, ejecuta la(s) acción(es): **log**,
   **webhook** y/o **detener el broadcast**.

Desactivado por defecto: sin proveedor + key no hace ninguna llamada ni gasta
nada.

## Proveedores soportados

| provider | Sirve para | Config mínima |
|---|---|---|
| `openai` | OpenAI **y cualquier API compatible con OpenAI**: Groq, Together, OpenRouter, Mistral, DeepSeek, un vLLM/llama.cpp local… | `apiKey`, `model`, opcional `baseUrl` |
| `anthropic` | Claude vision (API Messages de Anthropic) | `apiKey`, `model` (ej. `claude-sonnet-5`) |
| `generic` | **Cualquier otra API**: AWS Rekognition, Google Cloud Vision, Azure Content Safety, Sightengine, Hive, o tu propio microservicio, detrás de una URL | `baseUrl`, `apiKey`, `genericFlaggedPath`, `genericScorePath` |
| `disabled` | Apagado (por defecto) | — |

### El proveedor `generic` (la clave del "cualquier proveedor")

Hace `POST` a tu `baseUrl` con `{"image_base64":"…","mime":"image/jpeg"}` y la
API key en la cabecera que indiques (`genericApiKeyHeader` + `genericApiKeyPrefix`,
por defecto `Authorization: Bearer <key>`). Luego lee el resultado de la
respuesta JSON con rutas por puntos:

- `genericFlaggedPath` (booleano o número, ej. `result.moderation.flagged`)
- `genericScorePath` (número, ej. `result.moderation.score`)

Así envuelves cualquier servicio con un adaptador delgado y nunca tocas el
código del plugin. Las rutas soportan índices de array: `labels.0.name`.

## Configurar por REST

```bash
# Ejemplo: OpenAI
curl -X POST http://SERVIDOR:5080/LiveApp/rest/keeloke/v1/ai/config \
  -H "Content-Type: application/json" \
  -d '{
    "provider": "openai",
    "apiKey": "sk-...",
    "model": "gpt-4o-mini",
    "flagThreshold": 0.7,
    "sampleIntervalSeconds": 15,
    "actionOnFlag": "log,webhook",
    "webhookUrl": "https://tu-backend.example.com/ai-flags"
  }'

# Ejemplo: proveedor genérico (tu propio servicio de moderación)
curl -X POST http://SERVIDOR:5080/LiveApp/rest/keeloke/v1/ai/config \
  -H "Content-Type: application/json" \
  -d '{
    "provider": "generic",
    "baseUrl": "https://mi-moderador.example.com/analyze",
    "apiKey": "mi-token",
    "genericApiKeyHeader": "x-api-key",
    "genericApiKeyPrefix": "",
    "genericFlaggedPath": "result.flagged",
    "genericScorePath": "result.score",
    "flagThreshold": 0.7,
    "actionOnFlag": "webhook,stop",
    "webhookUrl": "https://tu-backend.example.com/ai-flags"
  }'
```

## Endpoints

```
GET  /keeloke/v1/ai/config                     -> config actual (apiKey redactada)
POST /keeloke/v1/ai/config                      -> reemplaza config (envía apiKey vacía o "***redacted***" para conservar la guardada)
GET  /keeloke/v1/ai/streams/{streamId}/last     -> último resultado de moderación del stream
POST /keeloke/v1/ai/streams/{streamId}/analyze  -> captura un frame ahora y lo analiza
```

## Propiedades

```
keeloke.ai.redisAddress=redis://127.0.0.1:6379
keeloke.ai.ffmpegPath=ffmpeg
```

## Límites honestos

- La calidad de la moderación es la del proveedor que elijas — el plugin no
  entrena ni ejecuta modelos por sí mismo, orquesta la llamada y actúa sobre el
  resultado.
- El muestreo por frames (cada N s) detecta contenido problemático con una
  latencia de hasta N segundos; no es análisis de cada fotograma en tiempo real
  (eso sería carísimo en cómputo y en coste de API). Baja `sampleIntervalSeconds`
  si necesitas más frecuencia, a cambio de más llamadas/coste.
- `actionOnFlag=stop` intenta detener el broadcast vía el adaptador del servidor;
  si la firma difiere entre versiones, el fallo se registra pero el flag ya se
  logueó/envió por webhook.
- Verificado: 23 aserciones unitarias sobre umbral, selección de proveedor,
  parsing de JSON del modelo y extracción por rutas del proveedor genérico. El
  build completo con Maven debe correrse antes de desplegar.
