# MeaCore Launcher (v1.5.9) 🚀

Un launcher de Minecraft de alto rendimiento, diseñado para ofrecer la mejor experiencia técnica y visual en entornos Linux (Ubuntu/Debian) y Windows.

## 🌟 Características Principales

- **🎮 Gestión de Perfiles Aislados**: Cada perfil tiene su propia carpeta de datos (`saves`, `screenshots`, `options.txt`), permitiendo configuraciones de mods y mundos totalmente independientes.
- **🚀 Auto-Optimización Inteligente**: Analiza la RAM disponible de tu PC para inyectar automáticamente los mejores argumentos de Java (ZGC Generacional, Aikar's Flags) y reducir el lag en versiones modernas (1.21+).
- **📡 Sincronización de Servidores NBT**: Gestiona tu lista de servidores multijugador directamente desde el launcher. Se sincronizan automáticamente con Minecraft antes de iniciar el juego.
- **🛡️ Gestión de Espacio**: Opción de eliminación permanente de perfiles que limpia automáticamente archivos residuales para optimizar tu almacenamiento.
- **⚡ Integración Nativa con el Sistema**: Identidad de ventana optimizada para GNOME (Ubuntu 24.04+) y soporte completo de telemetría de hardware (OSHI).

---

## 🌿 Arquitectura de Desarrollo

Este repositorio utiliza un flujo de trabajo dinámico para garantizar la estabilidad:

- **Rama `Estable`**: Versiones de producción validadas y listas para jugar. El motor gráfico está construido en JavaFX 21. 
- **CI/CD Automatizado**: Los instaladores nativos se generan automáticamente bajo etiquetas `bat-*`, sincronizando el nombre del archivo con la versión real del proyecto.
- **🔄 Auto-actualización Verificada**: Consulta las releases en segundo plano, descarga a un archivo temporal y solo instala si el SHA-256 coincide con el publicado por GitHub. Documentación completa en [`docs/UPDATES.md`](docs/UPDATES.md).

---

## 📥 Descarga e Instalación

¡Ya puedes descargar la última versión estable desde la sección de **[Releases](https://github.com/MeaCore-Enterprise/MeaCoreLauncher/releases)**!

### 🐧 Linux (Ubuntu/Debian)
1. Descarga el archivo `meacore-launcher_1.5.9_amd64.deb`.
2. **Terminal**:
   ```bash
   sudo apt install ./meacore-launcher_1.5.9_amd64.deb
   ```
3. Inicia "MeaCore Launcher" desde tu menú de aplicaciones.

### 🪟 Windows
1. Descarga el archivo `meacore-launcher-X.Y.Z.exe`.
2. Haz doble clic y sigue el asistente de instalación.

---

## 🛠️ Mejoras de Rendimiento (Auto-Optimización)
MeaCore no se limita a lanzar el juego. Analiza la **memoria física y libre** de tu equipo para seleccionar el motor de **Recolección de Basura (GC)** más eficiente para tu hardware específico. 

Implementa dinámicamente recursos de latencia ultra-baja como **ZGC Generacional** o configuraciones de precisión basadas en **Aikar's Flags**, garantizando una experiencia fluida incluso en las versiones pesadas de Minecraft modernas.

---

## 📡 Soporte y Bug Report
Si encuentras algún error o tienes una sugerencia, abre una **Issue** en este repositorio. ¡MeaCore está en constante evolución! 🛠️🫡✨
