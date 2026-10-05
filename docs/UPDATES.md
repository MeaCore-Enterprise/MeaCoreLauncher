# Auto-actualización

Documento de referencia del sistema de actualización del launcher: qué hace, en qué estados
puede estar, dónde guarda las cosas, cómo se publica una versión nueva y cómo se añade una
plataforma.

## Estados

No hay una máquina de estados con clase propia: los estados son la combinación de tres
`AtomicBoolean` de `service/AutoUpdateService.java` (guards de concurrencia) y los eventos
que emite `AutoUpdateService.UpdateListener`. Cada evento es un estado observable desde la UI.

| Estado | Guards | Evento que sale | Qué hace la UI |
|---|---|---|---|
| `IDLE` | — | — | Botón "Buscar Actualizaciones" activo |
| `CHECKING` | `checking=true` | `onCheckFinished` (siempre) | Botón en "Buscando...", deshabilitado |
| `COOLDOWN` | `checking=false` | `onCooldownSkipped` | No molesta al usuario; log informativo |
| `UP_TO_DATE` | `checking=false` | `onUpToDate` | Oculta el banner |
| `CHECK_FAILED` | `checking=false` | `onCheckError(String)` | Banner de aviso, botón rehabilitado |
| `UPDATE_FOUND` | `checking=false` | `onUpdateFound(version, url)` | Banner "Descargando..." |
| `DOWNLOADING` | `downloading=true` | `onDownloadProgress(fraction)` | Barra de progreso |
| `DOWNLOADED` | `downloading=false` | `onDownloadComplete(path)` | Botón "Reiniciar Ahora" |
| `UNVERIFIED` | `downloading=false` | `onUnverifiedDownload(path)` | Botón "Descargar manualmente" |
| `DOWNLOAD_FAILED` | `downloading=false` | `onDownloadError(String)` | Banner de error |
| `INSTALLING` | `installing=true` | — | "Instalando...", el launcher se cierra |

Reglas de transición que importan:

- Los tres guards son atómicos: nunca hay dos checks, dos descargas o dos instalaciones a la vez.
- `onCheckFinished` se emite en **todos** caminos de check, incluidos error y cooldown. Es la red
  de seguridad que garantiza que el botón vuelva a su estado.
- Un `onCheckError` **nunca** deshabilita el botón de instalar: son fallos independientes.
- La versión solo se marca `installed` **después** de que el instalador arranque bien. Si el
  script de Windows devuelve un exit code distinto de 0, el launcher no se relanza y la
  versión sigue disponible para reintentar.

## Flujo

```
check (GitHub API, máx. 1/hora)
  └─ evaluateAndNotify: compara SemVer con version.properties
       ├─ no es más nueva ......... onUpToDate
       ├─ ya instalada ........... no se ofrece
       ├─ descartada por el usuario  solo se silencia en checks automáticos
       └─ más nueva .............. onUpdateFound(version, url)
              └─ downloadAndInstallAsync
                   ├─ descarga a <instalador>.part (reanuda con Range si se puede)
                   ├─ compara tamaño (Content-Length y el "size" del manifest)
                   ├─ move atómico a <instalador>
                   ├─ SHA-256 vs "digest" del manifest
                   │    ├─ coincide ....... onDownloadComplete → instalar
                   │    └─ sin digest ..... onUnverifiedDownload → descarga manual
                   └─ el usuario pulsa "Reiniciar Ahora" → installFromPath
```

## Comparación de versiones

`util/SemVer.java` es la única fuente de verdad. Acepta `v1.2.3`, `V1.2.3`, `bat-1.2.3`,
`1.2`, `1` y sufijos `-alpha`/`-preview`. Compara según SemVer 2.0, así que `1.6.0-rc1` es
anterior a `1.6.0`.

Regla conservadora: `isNewerThan` devuelve `false` en cuanto cualquiera de las dos versiones
es inválida. Si el tag de la release o `version.properties` no se pueden interpretar, no se
ofrece nada: es preferible no molestar antes que instalar algo que no toca.

> El bug original comparaba el `tag_name` crudo (`"v1.6.1"`) contra `version.properties`
> (`"1.6.1"`), así que una instalación al día se detectaba a sí misma como nueva versión en
> cada arranque. De ahí el test `sameVersionWithTagPrefixIsNotOffered`.

## Integridad de la descarga

Nunca se escribe directamente sobre el instalador final:

1. Se descarga a `<archivo>.part`.
2. Se contrasta el tamaño con `Content-Length` y con el `size` que publica la API.
3. Se verifica el SHA-256 contra el campo `digest` (`sha256:...`) del asset.
4. Solo entonces se hace `Files.move(..., ATOMIC_MOVE)` al nombre definitivo.

Si algo falla, el `.part` se borra y **no queda ningún instalador a medias**.

Si el asset **no** trae `digest`, el launcher no instala automáticamente: muestra un botón
"Descargar manualmente" que abre la release en el navegador, donde GitHub muestra el hash
para que el usuario lo compare. Es preferible a ejecutar un binario sin verificar.

### Reanudación

Si queda un `.part` y el tamaño total es conocido, la siguiente descarga pide
`Range: bytes=N-` y continúa. Si el servidor ignora el Range (responde 200), se reinicia
desde cero en lugar de concatenar. Sin tamaño esperado **no** se reanuda: sin él no hay
forma de saber si el parcial pertenece a ese archivo.

## Estado persistente

`update-state.json`, en la carpeta de instaladores (`~/.cache/meacore/updates` en Linux,
`%LOCALAPPDATA%\MeaCore\updates` en Windows):

| Campo | Para qué sirve |
|---|---|
| `installedVersion` | No volver a ofrecer un instalador que ya se lanzó |
| `dismissedVersion` | No volver a molestar con la ✕ del usuario (solo checks automáticos) |
| `lastCheckMillis` | No gastar una petición de GitHub en cada arranque del launcher |

Si encuentra un `update-state.properties` de una versión anterior, lo lee y lo convierte a
JSON: los descartes del usuario no se pierden al actualizar. El archivo antiguo **no se
borra**.

Todo es best-effort: si el disco falla, el launcher sigue funcionando con la comparación de
versiones.

## Fuentes de la versión

El launcher pide, en este orden:

1. `https://api.github.com/repos/MeaCore-Enterprise/MeaCoreLauncher/releases/latest`
2. `https://meacorelauncher.meacore-enterprise.workers.dev/api/latest-release` (respaldo)

La API de GitHub sin autenticar está limitada a 60 peticiones/hora y devuelve **403** (no
429) al agotarse. `403`/`429` reciben un mensaje propio: es un límite de peticiones, no un
error del usuario, y se reintenta en el siguiente arranque.

### latest.json

El CI publica un asset `latest.json` en cada release:

```json
{
  "tag_name": "v1.7.0",
  "assets": [
    {
      "name": "meacore-launcher-1.7.0.exe",
      "browser_download_url": "https://github.com/OWNER/REPO/releases/download/v1.7.0/meacore-launcher-1.7.0.exe",
      "size": 73628160,
      "digest": "sha256:2b6cd6..."
    }
  ]
}
```

El SHA-256 lo calcula el propio CI **sobre el binario recién construido**, no GitHub. Es la
fuente preferida del Worker por dos motivos:

- La URL `https://github.com/<repo>/releases/latest/download/latest.json` **no consume cuota**
  de la API, así que el respaldo sigue respondiendo aunque el launcher haya agotado sus 60
  peticiones/hora o GitHub esté limitando al edge del Worker.
- El digest viene del artefacto real, no de un campo que rellena la API.

El Worker lo cachea 5 minutos (`Cache-Control: max-age=300`). Si `latest.json` da 404 (releases
antiguas, anteriores a este cambio) cae a la API; y si se define el secret
`GITHUB_READ_TOKEN` (solo lectura), lo usa para esa llamada. **No es necesario** para
`latest.json`.

## Instalar en Windows

`AutoUpdateService.windowsUpdaterScript()` genera el `.bat` que hace la actualización:

1. Espera 5 s y hace `taskkill` del launcher para liberar el ejecutable.
2. Arranca el instalador con `start /wait`, que deja `ERRORLEVEL` = exit code del instalador.
3. **Solo si el exit code es 0** relanza el launcher. Si no, escribe
   `meacore_update.log` junto al instalador, avisa en la ventana y **no** relanza.

El instalador ya viene verificado por SHA-256, así que **no** se usa `Unblock-File` ni
`-ExecutionPolicy Bypass`. Si el antivirus o SmartScreen bloquea el ejecutable, es mejor
decirlo que saltarse la protección en silencio.

El botón "Reiniciar Ahora" se bloquea si hay algún juego abierto (`activeProcesses`): el
instalador mata `MeaCore Launcher.exe` por nombre y cerraría el juego a mitad de la
actualización.

## Instalar en Linux

`pkexec` (o `sudo` como respaldo) con `apt install` / `dnf install` / `dpkg -i` según el gestor
detectado, y relanza `/opt/meacore-launcher/bin/MeaCore Launcher`. Si `pkexec` no existe, se
usa `sudo`, que necesita TTY o la entrada en `sudoers`.

Los datos del usuario van a `$XDG_DATA_HOME/MeaCoreLauncher` o `~/.local/share/MeaCoreLauncher`,
separados de `/opt/meacore-launcher`. Al arrancar se copian (nunca se mueven) los datos que
estuvieran en `/opt/meacore-launcher/launcher-data`, verificando cada archivo por tamaño.

## Cómo añadir una plataforma

Añadir un sistema nuevo toca cuatro sitios. El launcher **no** necesita recompilarse por
plataforma: elige el asset por extensión.

**1. CI: una entrada más en la matriz** de `.github/workflows/release.yml`

```yaml
matrix:
  include:
    - os: macos-latest
      target: macOS (.dmg)
```

**2. CI: un bloque `if: runner.os == 'macOS'`** que construya el instalador con `jpackage
--type dmg` y renombre el artefacto con la convención `meacore-launcher-<VERSION>.<ext>`.

**3. `AutoUpdateService`: elegir el asset correcto**

```java
// evaluateAndNotify()
String preferredExt = switch (platform()) {
    case WINDOWS -> ".exe";
    case LINUX   -> ".deb";
    case MACOS   -> ".dmg";
};
```

Hoy la selección es binaria (`isWindows() ? ".exe" : ".deb"`). Con más de dos plataformas,
sustituir por un `enum Platform` derivado de `os.name` y usarlo también en
`launchInstallerProcess()`.

**4. `launchInstallerProcess()`: cómo ejecutar el instalador**

Una rama por sistema, como las actuales de `.exe` (`.bat`) y `.deb` (`pkexec`). El
requerimiento común es: **comprobar que la instalación funcionó antes de cerrar el launcher**,
como hace el exit code del `.bat` en Windows.

**5. Verificación en la publicación**

`latest.json` y el paso *Verify both installers* enumerate las extensiones esperadas. Una
plataforma nueva debe añadirse ahí; si no, la release se publica sin su instalador.

**6. Tests**: `WindowsUpdaterScriptTest` cubre el script de Windows; el equivalente de la
plataforma nueva debería cubrir el suyo.

## Datos del usuario

Los datos (perfiles, versiones, librerías, assets) viven en un directorio **por usuario**:

- Windows: `%APPDATA%\MeaCoreLauncher`
- Linux: `$XDG_DATA_HOME/MeaCoreLauncher` o `~/.local/share/MeaCoreLauncher`

No viven junto al ejecutable: una actualización que cambia el directorio de instalación no
puede perder los perfiles.

Instalaciones antiguas tenían los datos en `<directorio de instalación>/launcher-data`. Al
arrancar se **copian** (nunca se mueven) al destino por usuario, verificando cada archivo por
tamaño. El origen se conserva intacto y el registro queda en `migration.log`. Si el destino ya
tiene datos, no se copia nada. Con `-Dlauncher.root=<ruta>` la raíz es explícita y no se
migra nada (modo portable).

## Publicar una versión

1. Actualiza `src/main/resources/version.properties`.
2. Crea la etiqueta (`v*` o `bat-*`) y haz push.

`.github/workflows/release.yml` hace el resto:

- Compila `.exe` y `.deb` **en paralelo** (matriz de dos sistemas).
- Genera `latest.json` con el SHA-256 de cada instalador, calculado sobre el binario real.
- Un único job `publish` adjunta los dos instaladores y el manifest a un draft.
- **Verifica que hay `.exe`, `.deb` y `latest.json`** y solo entonces publica la release.

Un fallo en un sistema deja la release sin publicar, nunca a medias. Reejecutar el workflow
sobre una release ya publicada sube los assets con `--clobber` sin borrarla, conservando su
ID y su fecha de publicación.

`fail-fast: false` y `concurrency` por etiqueta evitan que dos builds del mismo tag se
pisen.

## Tests

```
./gradlew build test
```

| Clase | Qué cubre |
|---|---|
| `util.SemVerTest` | Parseo, ordenación, pre-release, tags inválidos |
| `service.AutoUpdateServiceTest` | Decisión de ofrecer/no ofrecer, digest, mensajes de error |
| `service.UpdateStateTest` | JSON, migración desde `.properties`, `lastCheck` |
| `mojang.HttpFilesAtomicDownloadTest` | `.part`, move atómico, tamaños, reanudación con `Range` |
| `service.WindowsUpdaterScriptTest` | Exit code del instalador y relanzamiento condicional |
| `paths.LauncherDirectoriesMigrationTest` | Migración no destructiva de datos del usuario |

Las pruebas de descarga usan un servidor HTTP local: no dependen de la red.