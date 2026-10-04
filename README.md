# GBA Link P2P — Emulador de Game Boy Advance Nativo para Android con Multijugador P2P

Un emulador de **Game Boy Advance 100% nativo para Android** (escrito en **Kotlin y C++ con NDK**) diseñado específicamente para soportar **Cable Link multijugador online Peer-to-Peer directo**, **sin necesidad de abrir puertos** en el router ni configurar VPNs.

---

## Características Principales

* **100% Nativo de Android:**
  * Core de emulación de bajo nivel en **C++17** optimizado con CMake y NDK.
  * Interfaz de usuario, renderizado y audio en **Kotlin**.
  * Cero JavaScript, cero WebViews, sin capas intermedias.

* **Multijugador Online Peer-to-Peer (Cable Link GBA):**
  * Emulación del subsistema **SIO (Serial Input/Output)** del GBA:
    * Modo **Multi-player (16-bit)** para juegos como Pokémon (combates/intercambios en Cable Club), *Mario Kart: Super Circuit*, *The Legend of Zelda: Four Swords*.
    * Modo **Normal (16-bit / 32-bit)** para transferencias directas.
  * **Conexión directa P2P sin abrir puertos (NAT Traversal):**
    * Cliente **STUN RFC 5389** integrado que consulta servidores públicos de Google (`stun.l.google.com:19302`).
    * Descubrimiento automático de endpoints públicos y **UDP Hole Punching** bidireccional.
    * Funciona a través de redes WiFi domésticas, NAT simétrico/asimétrico y redes celulares móviles (4G/5G).

* **Controles y Experiencia Móvil:**
  * **Controles táctiles virtuales en pantalla:**
    * Cruceta (D-Pad) con seguimiento de 8 direcciones y deslizamiento.
    * Botones A y B ergonómicos.
    * Gatillos L y R superiores.
    * Botones Select y Start.
    * **Respuesta háptica** por vibración (`VibrationEffect`) en cada pulsación.
  * **Soporte para mandos físicos:** Compatible con mandos Bluetooth y USB (Xbox, PlayStation, 8BitDo, etc.).

* **Gestión de Juegos y Partidas:**
  * Selector de archivos SAF para abrir cualquier ROM de GBA (`.gba`).
  * ROM de prueba Homebrew integrada para probar la emulación de inmediato.
  * Exportación e importación de archivos de guardado de batería (`.sav`), compatibles con mGBA y MyBoy.

* **Apartado Nativo de Pokémon Showdown:**
  * **Conexión WebSocket directa** a los servidores oficiales de Pokémon Showdown (`sim3.psim.us`).
  * **Reservar / Iniciar sesión con apodo** consumiendo la API de autenticación oficial (`action.php`).
  * **Buscador de contrincantes (Ladder matchmaking)** en formatos como Gen 3 Random Battle, Gen 3 OU, Gen 9 Random Battle, etc.
  * **Retar contrincante directo** por nombre de usuario y notificaciones de retos entrantes para Aceptar/Rechazar.
  * **Arena de Combate 100% nativa:**
    * Barras de salud (HP) y estado en tiempo real para ambos Pokémon.
    * 4 botones táctiles grandes para los movimientos con contador de PP.
    * Cuadrícula de cambio de Pokémon (Switch).
    * Opciones de Rendirse (Forfeit) y temporizador (Timer).
    * Registro de eventos de la batalla en tiempo real.

---

## Estructura del Proyecto

```
Multiplayer/
├── app/
│   ├── build.gradle.kts                # Configuración Gradle del módulo Android y NDK
│   ├── src/main/
│   │   ├── AndroidManifest.xml         # Permisos de red, vibración y configuración de pantalla
│   │   ├── cpp/                        # Core de Emulación en C++ (NDK)
│   │   │   ├── CMakeLists.txt          # Compilación C++17 con flags -O3
│   │   │   ├── gba_types.h             # Tipos de datos, constantes y keymasks
│   │   │   ├── gba_link.h / .cpp       # Subsistema SIO (Serial I/O / Cable Link)
│   │   │   ├── gba_mmu.h / .cpp        # Gestor de memoria (BIOS, WRAM, VRAM, SRAM, I/O)
│   │   │   ├── gba_ppu.h / .cpp        # Picture Processing Unit (Scanline renderer)
│   │   │   ├── gba_apu.h / .cpp        # Audio Processing Unit (PCM Stereo 44.1 kHz)
│   │   │   ├── gba_cpu.h / .cpp        # ARM7TDMI 32-bit RISC con Thumb
│   │   │   ├── gba_core.h / .cpp       # Controlador del ciclo de frame (60 FPS)
│   │   │   └── jni_bridge.cpp          # Puente JNI entre Kotlin y C++
│   │   ├── java/com/multiplayer/gbalink/
│   │   │   ├── MainActivity.kt         # Actividad principal del juego
│   │   │   ├── core/
│   │   │   │   ├── GbaNative.kt        # Declaración JNI y callbacks
│   │   │   │   ├── GbaEmulator.kt      # Hilo de ejecución 60 FPS y AudioTrack
│   │   │   │   └── HomebrewRom.kt      # ROM demo de test integrada
│   │   │   ├── network/
│   │   │   │   ├── StunClient.kt       # Descubrimiento de IP pública mediante STUN
│   │   │   │   ├── LinkCableProtocol.kt# Protocolo de paquetes binarios para el Cable Link
│   │   │   │   └── P2PConnectionManager.kt # Orquestador UDP P2P y Hole Punching
│   │   │   └── ui/
│   │   │       ├── TouchControllerView.kt # Mando táctil virtual con vibración
│   │   │       └── MultiplayerDialog.kt   # Diálogo para Crear / Unirse a salas
│   │   └── res/                        # Layouts, colores y temas oscuros
├── build.gradle.kts                    # Gradle raíz
├── settings.gradle.kts                 # Repositorios y módulos
├── gradle.properties                   # Configuración JVM
├── local.properties                    # Rutas locales del SDK y NDK de Android
└── gradlew                             # Wrapper Gradle ejecutable
```

---

## Cómo Compilar y Ejecutar

### Opción 1: Desde Android Studio
1. Abre **Android Studio**.
2. Selecciona **Open** y navega a `/home/ben4shot/Develop/Multiplayer`.
3. Android Studio sincronizará Gradle y configurará automáticamente el toolchain C++ (CMake + NDK).
4. Conecta tu dispositivo Android (o inicia un emulador) y presiona **Run** (`Shift + F10`).

### Opción 2: Desde la Terminal con Gradle
Para compilar el APK de depuración directamente desde la consola:

```bash
cd /home/ben4shot/Develop/Multiplayer
./gradlew assembleDebug
```

El archivo APK generado se ubicará en:
`app/build/outputs/apk/debug/app-debug.apk`

Para instalarlo directamente en tu teléfono conectado por USB con depuración activada:

```bash
/opt/android-sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Cómo Jugar en Multijugador (Paso a Paso)

1. **Abrir el mismo juego en ambos teléfonos:**
   * En el Teléfono 1 y Teléfono 2, toca **"Cargar ROM"** y selecciona el mismo archivo `.gba` (ejemplo: *Pokémon Rojo Fuego / Esmeralda*).

2. **Crear la sala (Jugador 1 - Master):**
   * En el Teléfono 1, toca el botón **"Cable Link P2P"**.
   * Presiona **"Crear Sala (Host / Master)"**.
   * La app consultará el servidor STUN y generará un **Código de Sala** corto (ejemplo: `GBA-4921`).
   * Toca **"Copiar"** y envíale ese código a tu amigo por WhatsApp, Discord o mensaje.

3. **Unirse a la sala (Jugador 2 - Slave):**
   * En el Teléfono 2, toca **"Cable Link P2P"**.
   * Pega el código en el campo de texto y presiona **"Unirse a Sala (Cliente / Slave)"**.
   * Ambos teléfonos realizarán la perforación NAT UDP (Hole Punching) y se conectarán directamente punto a punto.
   * La barra superior mostrará: `Link: Conectado (XX ms)`.

4. **Iniciar la conexión en el juego:**
   * En el juego (por ejemplo en el Centro Pokémon, en el piso superior / Club del Cable):
   * Hablen con la recepcionista al mismo tiempo.
   * El emulador transferirá los paquetes SIO en tiempo real a través del túnel P2P directo.
   * ¡Aparecerán juntos en la sala de combate o de intercambio como si estuvieran conectados por un cable físico!
# gba_p2p_android
