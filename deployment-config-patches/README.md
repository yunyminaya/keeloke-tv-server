# Parches de configuración de despliegue

Copias de `WEB-INF/red5-web.xml` y `WEB-INF/web.xml` de las 3 apps
(`LiveApp`, `WebRTCApp`, `live`) **después** de aplicar los cambios
necesarios para que los plugins de `keeloke-plugins/` funcionen. Ver
`keeloke-plugins/VERIFIED_WORKING.md` para el detalle completo de por qué
cada cambio era necesario.

Resumen de los 2 cambios por archivo:

**`red5-web.xml`**: se agregó
`<context:component-scan base-package="tv.keeloke.plugins" />` y
`<ref bean="tenantQuotaPlugin"/>` dentro de `streamPublishSecurityList`.

**`web.xml`**: se agregó `,tv.keeloke.plugins` al valor de
`jersey.config.server.provider.packages` (para que los endpoints REST de
los plugins respondan bajo `/rest/keeloke/v1/...`).

Para aplicar esto a una instalación nueva de Keeloke TV Server 2.11.3, copia
estos 2 archivos por app sobre los tuyos (o aplica el mismo diff a mano si
tu instalación tiene otras personalizaciones).
