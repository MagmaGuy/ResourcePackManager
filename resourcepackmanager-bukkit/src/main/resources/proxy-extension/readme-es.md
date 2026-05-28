# ResourcePackManager — Plugin del Proxy

> **⚠ Este plugin solo es útil para servidores que ejecutan Geyser + Floodgate.**
> Su único propósito es fusionar el resource pack convertido a Bedrock de cada
> backend y entregarlo a los clientes Bedrock vía Geyser. **Los jugadores de
> Java Edition no necesitan este plugin** — la entrega de packs para Java en
> configuraciones de red la manejan directamente los backends individuales. Si
> tu red es solo Java (sin jugadores Bedrock que pasen por Geyser), puedes
> ignorar esta carpeta por completo.

Esta carpeta contiene los archivos JAR del **plugin del proxy de RSPM**. Estos van en
tu **proxy** (Velocity / BungeeCord / Waterfall), NO en el servidor backend de
Minecraft. El plugin del proxy fusiona el resource pack convertido a Bedrock de cada
backend en un solo pack y lo entrega a los clientes Bedrock a través de Geyser.

Si ejecutas un servidor independiente (sin proxy delante), puedes ignorar esta
carpeta por completo. Los JAR se extraen en cada arranque de todos modos, así que
están listos por si alguna vez añades un proxy más adelante.

---

## ¿Qué JAR debo usar?

Elige exactamente UNO según cuál sea tu software de proxy:

| Software de proxy        | Usa este JAR                             |
|--------------------------|------------------------------------------|
| Velocity                 | `ResourcePackManager-Velocity.jar`       |
| BungeeCord               | `ResourcePackManager-BungeeCord.jar`     |
| Waterfall (fork de Bungee) | `ResourcePackManager-BungeeCord.jar`     |

No instales ambos — solo el que corresponda. Instalar ambos provocará errores de
arranque en la plataforma que intente cargar el JAR equivocado.

---

## Instalación — 2 pasos

### 1. Copia el JAR al host del proxy

Copia el JAR correspondiente de ESTA carpeta al directorio `plugins/` de tu proxy.
Métodos comunes:

- **Misma máquina**: arrastra y suelta, o usa `cp` / `copy`.
- **Proxy remoto**: `scp ResourcePackManager-Velocity.jar usuario@host-proxy:/ruta/al/proxy/plugins/`
- **Panel de proxy alojado** (Pterodactyl, etc.): súbelo mediante el gestor de archivos del panel.

### 2. Reinicia el proxy

Listo. No hay configuración que editar, ni clave que pegar.

El plugin lee `plugins/floodgate/key.pem` en el proxy al arrancar y deriva la
identidad de red de ese archivo automáticamente. Como Floodgate ya requiere
que ese archivo sea el mismo en todos los backends Y en el proxy (para que la
autenticación Bedrock funcione), la clave derivada coincide con cada backend
automáticamente.

La primera fusión suele completarse en ~10 segundos después de que todos los
backends estén en línea.

---

## Cómo verificar que funciona

**Consola del proxy** (en ~10s tras el reinicio, asumiendo que los backends están corriendo):

```
[ResourcePackManager] Merged pack ready at .../merged/Bedrock.zip (sha1 ...)
[ResourcePackManager] Geyser mappings deployed to .../custom_mappings
[ResourcePackManager] ✔ Network resource pack is now ready (... KB, sha1ABCD1234)
```

**Cliente Bedrock**: conéctate vía Bedrock. Deberías ver el aviso de descarga del
resource pack antes de entrar al mundo. Los ítems personalizados se renderizan
con sus modelos previstos en lugar de armor stands planos.

**`/rspm status`** en el backend: muestra el estado del pack, el modo de hosting
y la huella de la network-key. Compara los últimos 4 caracteres con la config del
proxy para confirmar que ambos lados están enlazados.

---

## Problemas comunes

### "Floodgate key.pem missing" al arrancar el proxy

El plugin del proxy no pudo encontrar `plugins/floodgate/key.pem` y se quedó
inactivo. Solución:

1. **Instala Floodgate** en el proxy. Es necesario para que los jugadores
   Bedrock lleguen al proxy de todos modos, así que esto hace falta
   independientemente de RSPM.
2. Asegúrate de que `plugins/floodgate/key.pem` en el proxy es **idéntico
   byte por byte** al mismo archivo en cada backend. Floodgate genera
   automáticamente claves distintas por instalación por defecto — copia un
   `key.pem` canónico desde cualquier backend a todos los demás componentes
   (otros backends + el proxy), luego reinicia todo. Floodgate ya lo requiere
   para la autenticación Bedrock, así que si los jugadores Bedrock funcionan
   actualmente en tu red, esto ya está hecho.

### Bedrock conecta pero no ve los modelos personalizados

Causa más común: el proxy arrancó ANTES de que el backend produjera su primer
pack de Bedrock. Geyser registra los ítems personalizados solo al arrancar — una
vez que está corriendo con un archivo de mappings vacío, así se queda. **Reinicia
el proxy** después de que el backend haya registrado `Wrote merged Geyser mappings:
N entries`. Las fusiones posteriores son recogidas automáticamente por la ruta
de entrega de packs de Geyser, pero la tabla de ítems personalizados se fija al arrancar.

### Advertencias "Duplicate bedrock_identifier" al arrancar el proxy

Dos backends emitieron el mismo identificador Bedrock para el mismo ítem base.
Gana el último escritor; inofensivo si solo necesitas que un backend provea ese
ítem. Si ambos backends deberían alojar ítems personalizados distintos bajo el
mismo ítem base, tienes una colisión real — renombra uno de los modelos Java
de origen para que los hashes autogenerados difieran.

### Actualización de RSPM

Después de actualizar el JAR del backend de RSPM, vuelve a copiar también el
correspondiente `ResourcePackManager-Velocity.jar` / `-BungeeCord.jar` de esta
carpeta al proxy y reinicia el proxy. El backend los regenera en cada arranque,
así que siempre están sincronizados con la versión del backend.

---

Generado por ResourcePackManager v${version} en el arranque del backend.
Otros idiomas disponibles: ver `README.md` (inglés / English), `README - francais.md`,
`README - portugues.md`, `README - zhongwen.md`, `README - hindi.md`,
`README - bangla.md`, `README - arabi.md`, `README - russkij.md`, `README - urdu.md`.
