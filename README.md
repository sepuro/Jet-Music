<div align="center">
  <a href="https://jetmusic.site">
    <img src="assets/banner.svg" alt="Jet Music — Sonido puro. Diseño minimalista a 120Hz." width="100%" />
  </a>

  <br />
  <br />

  <p>
    <a href="https://jetmusic.site"><img src="https://img.shields.io/badge/Web_Oficial-jetmusic.site-070709?style=for-the-badge&logoColor=F5F5F7&labelColor=121216" alt="Web Oficial jetmusic.site" /></a>
    <a href="https://github.com/sepuro/Jet-Music/releases/latest"><img src="https://img.shields.io/github/v/release/sepuro/Jet-Music?style=for-the-badge&label=Release_APK&color=F5F5F7&labelColor=121216" alt="Último Release" /></a>
    <a href="https://github.com/sepuro/Jet-Music/actions/workflows/android-build.yml"><img src="https://img.shields.io/github/actions/workflow/status/sepuro/Jet-Music/android-build.yml?branch=main&style=for-the-badge&label=CI_%26_Firma_v2%2Fv3&labelColor=121216" alt="Estado CI/CD" /></a>
    <a href="LICENSE"><img src="https://img.shields.io/badge/Licencia-GPL_v3.0-121216?style=for-the-badge&labelColor=070709" alt="Licencia GPL-3.0" /></a>
  </p>

  <p>
    <b>Reproductor de música minimalista de alto rendimiento para Android.</b><br />
    Audio sin pérdidas • Dolby Atmos • Music Flow • Letras sincronizadas a 120Hz • Cápsulas Apple Glass • 100% Open Source y sin anuncios.
  </p>

  <p>
    <a href="https://jetmusic.site"><b>🌐 Sitio Web Oficial (jetmusic.site)</b></a>
    &nbsp;•&nbsp;
    <a href="https://github.com/sepuro/Jet-Music/releases/latest"><b>⬇️ Descargar APK v1.7.0 (Release Firmado)</b></a>
    &nbsp;•&nbsp;
    <a href="#características-principales"><b>✨ Características</b></a>
    &nbsp;•&nbsp;
    <a href="#arquitectura-y-compilación"><b>🛠️ Compilación</b></a>
  </p>
</div>

---

## Descripción General

**Jet Music** combina una arquitectura de audio de grado audiófilo con una interfaz minimalista inspirada en **Apple Music**, diseñada sobre **Jetpack Compose** y **Media3** para ejecutarse a **120 FPS constantes** sin recomposiciones innecesarias.

Cada versión se compila en modo `Release` optimizado con **R8 Full Mode**, utilizando el identificador de paquete exclusivo `app.jetmusic.android` y verificación criptográfica obligatoria mediante `apksigner` (**APK Signature Scheme v2 y v3**) en GitHub Actions.

---

## Características Principales

### 1. Audio sin pérdidas & Dolby Atmos
- **Streaming Lossless y Hi-Res**: Reproducción de flujos FLAC y ALAC sin compresión de hasta **24-bit / 192 kHz**.
- **Pipeline Bit-Perfect por USB DAC**: Derivación directa del mezclador de Android para enviar audio puro al convertidor digital-analógico externo.
- **Motor DSP Nativo y Dolby Atmos**: Ecualizador paramétrico de 10 bandas con espacialización acústica y telemetría en tiempo real para códecs Bluetooth de alta resolución (**LDAC**, **aptX HD**, **LC3**).
- **Insignias de Formato en Vivo**: Indicadores precisos de calidad (`FLAC`, `Hi-Res`, `Bit-Perfect`, `Dolby Atmos`) integrados en la pantalla de reproducción.

### 2. Music Flow & Letras Sincronizadas en Tiempo Real
- **Modo Music Flow (Video Canvas)**: Transición instantánea y fluida entre reproducción de audio puro y lienzos de video sincronizados.
- **Letras Sincronizadas Sílaba por Sílaba**: Seguimiento en tiempo real con jerarquía tipográfica estilo *San Francisco Display* (`FontWeight.ExtraBold` en la línea activa, escala reactiva y transición suave).
- **Traducción Integrada**: Traducción automática de letras dentro del reproductor sin interrumpir la experiencia visual.

### 3. Interfaz Minimalista a 120Hz (Light & Dark)
- **Aislamiento Total del Estado de Reproducción**: Lectura diferida mediante lambdas proveedoras en la fase de dibujado (`drawWithContent` / `graphicsLayer`), garantizando **0 recomposiciones** en `HomeScreen` mientras avanza la canción o se hace scroll.
- **Carátula Reactiva con Físicas de Resorte**: En el reproductor expandido, la portada responde dinámicamente al estado de reproducción (`scale = 1.0f` y sombra de `24.dp` al reproducir; `scale = 0.85f` y sombra de `8.dp` en pausa) usando `spring(DampingRatioLowBouncy, StiffnessLow)`.
- **Cápsulas Flotantes en Acabado Apple Glass**: `MiniPlayer` y barra de navegación inferior con superficies translúcidas de alto contraste (`#141416` en Dark / `#F5F5F7` en Light) y borde especular biselado de `0.8.dp`, pre-cacheados con `Modifier.drawWithCache`.

### 4. Actualizaciones OTA Integradas & 100% Open Source
- **Actualizador OTA Nativo**: Verificación automática al iniciar la app y manual desde Ajustes contra la API oficial de GitHub Releases (`/repos/sepuro/Jet-Music/releases/latest`) con comparador semántico estricto (`major.minor.patch`).
- **Descarga e Instalación Directa**: Hoja modal minimalista con notas de la versión, barra de progreso real e invocación directa del instalador del sistema vía `FileProvider` (`${applicationId}.fileprovider`).
- **Privacidad Absoluta**: 100% libre de anuncios, suscripciones y rastreadores comerciales.

---

## Capturas de Interfaz

<div align="center">
  <table>
    <tr>
      <td align="center">
        <b>Reproductor Estilo Apple Music</b><br /><br />
        <img src="Screenshots/Apple%20inspired%20music%20page.png" alt="Reproductor Estilo Apple Music" width="240" />
      </td>
      <td align="center">
        <b>Letras Sincronizadas 120Hz</b><br /><br />
        <img src="Screenshots/lyrics.png" alt="Letras Sincronizadas" width="240" />
      </td>
      <td align="center">
        <b>Cápsulas Flotantes Apple Glass</b><br /><br />
        <img src="Screenshots/Liquid%20Glass%20Apple%20Inspired.png" alt="Cápsulas Flotantes Apple Glass" width="240" />
      </td>
    </tr>
    <tr>
      <td align="center">
        <b>Reproductor Material</b><br /><br />
        <img src="Screenshots/Material%20you%20music%20page.png" alt="Reproductor Material" width="240" />
      </td>
      <td align="center">
        <b>Explorar y Buscar</b><br /><br />
        <img src="Screenshots/search%20page.png" alt="Explorar y Buscar" width="240" />
      </td>
      <td align="center">
        <b>Biblioteca Musical</b><br /><br />
        <img src="Screenshots/library.png" alt="Biblioteca Musical" width="240" />
      </td>
    </tr>
  </table>
</div>

---

## Instalación

### Descarga Directa (Recomendado)
1. Visita la web oficial **[https://jetmusic.site](https://jetmusic.site)** o la página de **[GitHub Releases](https://github.com/sepuro/Jet-Music/releases/latest)**.
2. Descarga el archivo firmado **`Jet-Music-v1.7.0-release.apk`**.
3. Instala el APK en tu dispositivo Android (Android 8.0 / API 26 o superior, arquitecturas `arm64-v8a` y `armeabi-v7a`).
4. Las versiones posteriores se notifican e instalan directamente desde el **sistema OTA integrado** dentro de la aplicación.

---

## Arquitectura y Compilación

<details>
<summary><b>Compilar desde el código fuente (Modo Release Optimizado)</b></summary>
<br />

1. **Clonar el repositorio**
   ```bash
   git clone https://github.com/sepuro/Jet-Music.git
   cd Jet-Music
   ```

2. **Configurar Android SDK**
   Define la ruta de tu SDK en `local.properties`:
   ```bash
   echo "sdk.dir=/ruta/a/tu/android/sdk" > local.properties
   ```

3. **Ejecutar compilación Release firmada (R8 + Resource Shrinking)**
   ```bash
   ./gradlew lintVitalUniversalFossRelease assembleUniversalFossRelease --stacktrace
   ```

4. **Verificar firma criptográfica v2/v3 y metadatos del APK**
   ```bash
   apksigner verify --verbose --print-certs app/build/outputs/apk/universalFoss/release/*.apk
   aapt2 dump badging app/build/outputs/apk/universalFoss/release/*.apk | grep -E "package:|launchable-activity:"
   ```

</details>

| Especificación Técnica | Valor Oficial |
| :--- | :--- |
| **Nombre de la Aplicación** | `Jet Music` |
| **Application ID (`package`)** | `app.jetmusic.android` |
| **Versión Actual** | `1.7.0` (`versionCode = 170`) |
| **SDK Mínimo / Objetivo / Compilación** | `minSdk 26` • `targetSdk 36` • `compileSdk 37` |
| **Arquitecturas Nativas (ABI)** | `arm64-v8a`, `armeabi-v7a` |
| **Esquema de Firma** | `APK Signature Scheme v2 & v3` (`RSA 2048-bit`) |
| **Dominio Web Oficial** | [https://jetmusic.site](https://jetmusic.site) |

---

## Aviso Legal y Licencia

1. **100% Gratuito, Open Source y No Comercial**: Jet Music es un proyecto de software libre publicado bajo la licencia [GPL-3.0](LICENSE) con fines educativos y de uso personal. No contiene anuncios, suscripciones ni monetización.
2. **Sin Alojamiento de Contenido**: Jet Music no almacena, aloja ni distribuye archivos multimedia con derechos de autor en servidores propios; funciona exclusivamente como un cliente especializado sobre flujos públicos.

---

<div align="center">
  <p>
    <a href="https://jetmusic.site"><b>jetmusic.site</b></a> • <a href="https://github.com/sepuro/Jet-Music"><b>github.com/sepuro/Jet-Music</b></a> • Licenciado bajo <a href="LICENSE"><b>GPL-3.0</b></a>
  </p>
</div>
