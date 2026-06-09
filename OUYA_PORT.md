# AssaultCube → OUYA Port — Build & Engineering Log

End-to-end record of porting **AssaultCube** (Cube engine FPS) to the **OUYA** console
(Tegra 3, Android 4.1 / API 16, OpenGL ES 2.0 only, armeabi-v7a).

- **Result:** boots to a fully-rendered 3D menu and loads into a playable map on real OUYA
  hardware; gl4es translates the engine's immediate-mode OpenGL to GLES2; OpenAL audio works;
  the OUYA controller is mapped to gameplay + menus.
- **Base:** official `assaultcube/AC` repo, branch **`acmobile`** (already gl4es + SDL2 + SDL2_image).
- **Android project:** `source/android/` (Gradle + CMake `externalNativeBuild`).
- **Test device:** OUYA, ADB over Wi-Fi (`<OUYA-IP>:5555` — substitute your console's LAN address).

---

## 1. Target hardware constraints

| Spec | Value | Consequence |
|------|-------|-------------|
| SoC | NVIDIA Tegra 3, quad Cortex-A9 | `armeabi-v7a` |
| GPU | GeForce ULP — **OpenGL ES 2.0 max** | gl4es with its **GLES2** backend; no GLES3 |
| OS | Android 4.1 = **API 16** | `minSdk`/`APP_PLATFORM` 16; legacy-API pitfalls |
| Input | Bluetooth pad (no touchscreen) | gamepad must drive a game built for touch + KB/mouse |

## 2. Why the `acmobile` branch

AssaultCube has an official mobile branch that already renders via **gl4es + SDL2 + SDL2_image**,
and its `app/build.gradle` literally documents `platformVersion = 12 // openGLES 2 min api level`
while the manifest declares `glEsVersion 0x00020000`. GLES2 is therefore a *supported* configuration;
only the conservative default (minSdk 24 / GLES3.2) needed lowering. The acmobile developer had also
already stripped the HID/Bluetooth Java, so the API-18 `BluetoothManager` crash that the SM64 port hit
does **not** recur here.

## 3. Toolchain (this machine)

- NDK **23.2.8568313** (last NDK that still targets `android-16` on armeabi-v7a — do not use r24+).
- SDK CMake **3.22.1** + bundled `ninja`.
- Portable **Temurin JDK 11** (`_jdk11/jdk-11.0.31+11`) — AGP 7.0.2 / Gradle 7.0.2 need Java ≤ 16.
- Build through the space-free junction **`C:\m64`** (`mklink /J C:\m64 "...\Mario 64 Ouya"`); `ndk-build`
  breaks on the space in the real path.
- `adb` from `%LOCALAPPDATA%\Android\Sdk\platform-tools`.

## 4. Native dependencies — none shipped in the repo, all cross-compiled

`source/android/app/src/main/cpp/lib/` and `lib_src/` are **gitignored**; the prebuilt deps are not in
the repo. All six were cross-compiled for **armeabi-v7a / android-16** and staged into
`cpp/lib/<dep>/armeabi-v7a/`:

| Dep | Output | How |
|-----|--------|-----|
| **gl4es** (ptitSeb) | `libGL.a` (static) | its bundled `Android.mk` via `ndk-build` (sets `-DNOX11 -DNO_GBM -DDEFAULT_ES=2 -DSTATICLIB`, `BUILD_STATIC_LIBRARY`) |
| **SDL2 2.0.14** | `libSDL2.so` (+ `libhidapi.so`, `libc++_shared.so`) | SDL's `Android.mk` via `ndk-build`. 2.0.14 chosen to match the bundled `org.libsdl.app` Java |
| **SDL2_image 2.0.5** | `libSDL2_image.so` | combined `ndk-build` project including both SDL2 and SDL2_image `Android.mk` (`SUPPORT_WEBP=false`; JPG+PNG only) |
| **libogg / libvorbis / libvorbisfile** (xiph) | `.so` | CMake + NDK toolchain |
| **OpenAL-Soft 1.21.1** (kcat) | `libopenal.so` | CMake (`-DLIBTYPE=SHARED -DANDROID_STL=c++_shared`, utils/examples/tests off) |

CMake cross-compile invocation:
```
cmake -S <src> -B <build> -G Ninja -DCMAKE_MAKE_PROGRAM=<sdk>/cmake/3.22.1/bin/ninja.exe \
  -DCMAKE_TOOLCHAIN_FILE=<ndk>/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=armeabi-v7a -DANDROID_PLATFORM=android-16 -DBUILD_SHARED_LIBS=ON -DCMAKE_BUILD_TYPE=Release
```

ndk-build invocation (per lib):
```
ndk-build NDK_PROJECT_PATH=<dir> APP_BUILD_SCRIPT=<dir>\Android.mk NDK_APPLICATION_MK=<dir>\Application.mk
# Application.mk: APP_ABI := armeabi-v7a / APP_PLATFORM := android-16
```
Combined SDL2 + SDL2_image build: a top `Android.mk` that captures `my-dir` into a **private var
before** the two `include`s (SDL2's include clobbers `LOCAL_PATH`).

### CMakeLists.txt edits (`cpp/CMakeLists.txt`)
- Added an imported `vorbisfile` lib and linked it — the engine's `oggstream.cpp` uses
  `ov_open_callbacks` / `ov_read` / `ov_clear`, which live in **libvorbisfile**, not libvorbis (upstream
  only linked `vorbis` + `ogg`).
- Added an imported `hidapi` lib (libSDL2.so has a `DT_NEEDED` on `libhidapi.so`) and linked it so AGP
  packages it.

## 5. Gradle / build wiring (`source/android`)

- `app/build.gradle`: `platformVersion 16`, `ndkVersion '23.2.8568313'`, `abiFilters 'armeabi-v7a'`.
- `build.gradle` (root): replaced dead `jcenter()` with `mavenCentral()`.
- `local.properties`: `sdk.dir` pointed at the original developer's path → fixed to this machine's SDK.
- AndroidX left disabled (default) so the `com.android.support` + `com.google.android.play:core` deps
  resolve; the Play Core in-app-review code is inert on a sideloaded OUYA.
- Assets: `copyassets` copies `bot/ config/ packages/` from the repo root into `app/src/main/assets`
  (~50 MB of **free** AssaultCube content — no proprietary game data needed).
- Build: `JAVA_HOME=<jdk11>` then `gradlew assembleDebug --no-daemon` from `C:\m64\AssaultCube\source\android`.
- Output: `app-debug.apk` (~56 MB), package `net.cubers.assaultcube`, launcher `.LaunchActivity`
  (`.AssaultCubeActivity` is **not** exported — launch via LaunchActivity).

## 6. Runtime fixes (each was a distinct crash, fixed in order)

1. **`NoSuchMethodError android.text.Html.fromHtml(String,int)`** (API 24) in `LaunchActivity.showTerms`
   → guard with `Build.VERSION.SDK_INT >= 24`, else the legacy single-arg overload.
2. **`NoClassDefFoundError java.nio.charset.StandardCharsets`** (API 19) in
   `LaunchActivity.updateFromMasterserver` → replace `StandardCharsets.UTF_8` with `"UTF-8"`; removed the
   unused `java.nio.file` imports. (Also added connect/read timeouts to the masterserver HTTPS call so it
   can't hang the "Please wait…" screen — it still fails by SSL on this old device, but harmlessly.)
3. **API-16 dynamic linker does not resolve transitive `DT_NEEDED` from the app lib dir.** Each native
   library must be `System.loadLibrary`'d explicitly in dependency order. Overrode
   `AssaultCubeActivity.getLibraries()` to return, in order: `c++_shared, hidapi, ogg, vorbis,
   vorbisfile, openal, SDL2, SDL2_image, main` (`main` must be last — SDL uses it as the main object).
4. **`EGL_BAD_DISPLAY` at `SDL_GL_CreateContext`** → in `main.cpp setupscreen()` request an ES 2.0
   context before window creation: `SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK,
   SDL_GL_CONTEXT_PROFILE_ES)` + major 2 / minor 0.
5. **★ Tegra 3 Cg shader compiler SIGSEGV (`libcgdrv.so CgDrv_Compile`)** — the real OUYA blocker.
   gl4es' `GetHardwareExtensions()` compiles hardware-probe test shaders at init using ES3/desktop GLSL
   syntax (`layout(location=…)` / `in` qualifiers) which makes the ancient Tegra Cg compiler crash. Fix:
   set **`LIBGL_NOTEST=1`** (skips the probe shaders *and* gl4es' own EGL pbuffer probe context — a second
   `EGL_BAD_DISPLAY` source — using safe GLES2 defaults). gl4es reads `LIBGL_*` in a `constructor(101)`
   that runs at libmain.so load (before `main()`), so the env vars are set from an earlier
   `__attribute__((constructor(100)))` in `main.cpp` via `setenv()`. Also set `LIBGL_NOHIGHP=1`,
   `LIBGL_NOPSA=1`, `LIBGL_NOBANNER=1`, `LIBGL_ES=2`. (gl4es' `printf` → logcat tag `LIBGL`;
   `LIBGL_DBGSHADERCONV=3` dumps shaders if needed.)
6. **Data path.** `stream.cpp absbasedir` was hardcoded to `/storage/emulated/0/Android/data/.../files/`
   (an API-17+ path); the API-16 OUYA uses `/mnt/sdcard` (`/sdcard`), so the engine couldn't find
   `config/font.cfg` and fatal'd. Fix: resolve it at runtime via `SDL_AndroidGetExternalStoragePath()`
   (the same dir the Java `AssetExporter` writes to) in a new `setupandroidbasedir()` called at the top of
   `main()`'s `#elif __ANDROID__` block.

## 7. Controller mapping

AssaultCube is a keyboard+mouse FPS with **no native gamepad support**, and on Android the input path is
`if(touchenabled()) checktouchinput(); else checkinput();` — i.e. the desktop KB/mouse handler never runs.
The OUYA has no touchscreen, so the gamepad is mapped explicitly:

- `SDL_INIT_GAMECONTROLLER` added to `SDL_Init`; the pad is opened lazily (`SDL_GameControllerOpen`).
- A new `processgamepad()` is called **unconditionally each frame** from the main loop (right after the
  touch/`checkinput` branch) so it runs on Android.
- It feeds `mousemove()` for look, the engine's weapon/scoreboard commands via `execute()`
  (`shiftweapon`, `weapon`, `reload`, `setscope`, `showscores`), and the movement/fire/jump/crouch
  actions via **direct function calls** (see the critical fix below).
- **★ Critical fix — the engine's `"d"` (down-state) commands can't be driven by `execute()`.**
  `forward/backward/left/right/attack/jump/crouch` are registered with sig `"d"`; `command.cpp` invokes
  them as `((void(*)(bool))fun)(addreleaseaction(name)!=NULL)`, i.e. the bool comes from the *key-press
  context*, which is NULL outside a real key event — so `execute("forward 1")` always calls
  `forward(false)` (a no-op). The symptom was that only `shiftweapon` (sig `"i"`) and `mousemove`
  responded while movement/fire/jump/crouch did nothing. Fix: declare and **call the functions directly**
  (`extern void forward(bool)/backward/left/right/attack/jumpn/crouch;` — jump's function is `jumpn`),
  through a `padmovef(fn, &prev, down)` helper. Non-`"d"` commands work fine via `execute()`.
- **2nd subtlety:** `checktouchinput()` runs *before* `processgamepad()` each frame and resets movement
  to neutral, so held actions are **re-asserted every frame** (`fn(true)` while held, `fn(false)` once on
  release) instead of edge-only — otherwise the touch layer cancels stick movement on the next frame.
- Menu state is detected via `gamepadmenuopen()` (a helper added in `menus.cpp` returning
  `curmenu && curmenu->allowinput`); in a classic menu the pad sends navigation keysyms. (The polished
  *touchui scenes* — main/server/battleground pickers — are not `curmenu`, so pad navigation of those is
  still TODO, tracked with the multiplayer work.)
- The Ouya pad's SDL GameController mapping is itself correct (GUID
  `050000006666616600000000ffff3f00`: `a:b0 b:b1 x:b2 y:b3 leftx:a0 lefty:a1 rightx:a2 righty:a3
  lefttrigger:a4 righttrigger:a5 …`; triggers rest at `-32768`).

Layout (coherent console-FPS):

| Control | Action |
|---------|--------|
| Left stick | move (forward/back/strafe) |
| Right stick | look (`gamepadlooksens`, `gamepaddeadzone` cvars, persisted) |
| R2 / L2 | fire / aim (scope) |
| R1 / L1 | next / previous weapon |
| A / B | jump / crouch |
| X / Y | reload / knife (melee) |
| D-pad U/D/L/R | grenade / pistol / prev weapon / next weapon |
| Back (Select) | scoreboard (hold) |
| Start | open/close menu |
| *In a menu (classic):* D-pad or left stick | navigate; A = confirm; B / Start / Back = back |

### On-screen touch controls
The bundled mobile build draws virtual touch controls (movement stick, look pad, fire/jump/weapon/reload
icons). Since the gamepad replaces them, a `touchcontrols` cvar (added in `touch.cpp`, default **0**) hides
them: in `touch/core/hud.h draw()` the **menu button stays always visible**, while the help icon and the
whole gameplay-controls branch are gated behind `if(touchcontrols)`. (Touch input handlers are left
intact and harmless — the Ouya has no touchscreen.)

## 8. Soft-keyboard text entry (nickname / text fields)

Typing letters worked, but the on-screen keyboard's **ENTER never validated** a field (e.g. changing the
player name in the touchui), and pressing **Back** closed the keyboard yet left the field "stuck" in edit
mode. The root cause is entirely in SDL2 2.0.14's Android text-input pipeline, not the engine:

- The soft-keyboard Enter goes through native `Java_org_libsdl_app_SDLActivity_onNativeSoftReturnKey()`
  (`SDL_android.c`), which is a **no-op unless the hint `SDL_HINT_RETURN_KEY_HIDES_IME` is set** — so the
  Enter was swallowed and **never reached native code at all** (letters arrive separately via
  `commitText → SDL_TEXTINPUT`). Handling `SDL_SCANCODE_RETURN` or a newline `SDL_TEXTINPUT` in the engine
  could therefore never catch it: there is no event.
- Back calls native `onNativeKeyboardFocusLost() → SDL_StopTextInput()`, which hides the keyboard and makes
  `SDL_IsTextInputActive()` return false — but the touchui `textview` never reacted to that deactivation.

**Fix (IME-agnostic, two parts):**
1. `main.cpp` (next to the other Android `SDL_SetHint`s): `SDL_SetHint(SDL_HINT_RETURN_KEY_HIDES_IME, "1")`
   so the keyboard Enter triggers `SDL_StopTextInput()`.
2. `touch/core/textview.h`: each frame in `render()`, if `editing && !SDL_IsTextInputActive()` call a new
   `commitedit()` that applies the value and leaves edit mode — bubbling `UIE_TEXTCHANGED` / `UIE_TEXTENDEDIT`
   straight to the **parent** via `view::bubbleevent` (not through `focuslost()/this->bubbleevent`, which
   toggles the IME and would *re-open* the keyboard since text input is already inactive). It commits on the
   **`SDL_IsTextInputActive()` falling edge**, so it fires for both Enter (via the hint) and Back. This is
   the same pattern the classic menu already used (`main.cpp`: `wasediting && !editingnow → menutextinputcommit`).

## 9. Launch robustness — the "Please wait…" splash

`LaunchActivity` shows a "Please wait…" splash while a single background `AsyncTask` runs
`exportAssets() → updateFromMasterserver() → startGame()`. Several ways it could hang were hardened:

- **Always finish:** the pipeline body is wrapped in `try { … } finally { startGameSafely(); }`, so any
  uncaught exception/Error (e.g. `getExternalFilesDir()` momentarily null, an IO/runtime error, OOM) still
  launches the game instead of stranding the splash.
- **Run once:** `termsAccepted()` is guarded by an `AtomicBoolean` (`startupBegun.compareAndSet`). `onResume`
  can fire repeatedly on the OUYA, and once the terms are accepted each resume would otherwise start another
  concurrent export pipeline.
- **★ Stall watchdog (the real failure mode):** the asset export is a ~50 MB file-by-file copy from the APK
  to the external files dir, only triggered when `versionCode` changes (`AssetExporter` skips it when the
  stored version matches). On the OUYA's slow flash — **and this unit runs Xposed, which hooks the zygote
  and can stall heavy I/O** — the copy loop was observed to **block on a write without throwing**, so the
  `try/finally` never ran and the splash hung forever. Fixes:
  - `AssetExporter.copyFile` buffer **1 KB → 64 KB** + `BufferedOutputStream` (export goes from minutes to
    seconds when it does run).
  - `AssetExporter.sLastProgressMs` (a `volatile`) is bumped per file copied.
  - `LaunchActivity.startExportWatchdog()` polls that timestamp every 3 s; if it has not advanced for 12 s
    the export is assumed wedged and the game is launched anyway. A legitimately slow-but-progressing export
    keeps bumping the timestamp, so the watchdog never misfires.
  - `startGameSafely()` is gated by an `AtomicBoolean gameStarted` so `startGame()` runs exactly once,
    whichever path (pipeline finally, or watchdog) gets there first.

> **Dev tip:** to deploy a config-only change *without* the slow re-export, push the file to
> `/sdcard/Android/data/net.cubers.assaultcube/files/config/` and
> `echo <versionCode> > …/files/androidappversioncode.txt` so `isAssetExportRequired()` sees a match.

## 10. Performance pass (Tegra 3 is fillrate / bandwidth bound)

The OUYA composites a full **1920×1080** surface; on the ULP GeForce that is the bottleneck on complex maps.
Three levers, all confirmed on device:

- **★ Render-resolution downscale (biggest win).** `SDLActivity.sRenderMaxHeight` (px, default **720**;
  `0` = native). In `SDLSurface.surfaceChanged()`, if the incoming height exceeds the target,
  `holder.setFixedSize(targetW, targetH)` (e.g. 1280×720) and early-return; the re-fired `surfaceChanged`
  at the reduced size drives the engine (which reads `scr_w/scr_h` from `SDL_GetWindowSize`). ~2.25× fewer
  pixels at 720p. Drop to 540 (960×540) for more FPS, raise toward 1080 for sharpness.
  - **★★ Fullscreen gotcha.** `setFixedSize` alone made the Nvidia HWC composite the 720p buffer as a
    1280×720 overlay **in the top-left corner** (small / "windowed"), because SDL added its `SurfaceView`
    with default `WRAP_CONTENT` params (`mLayout.addView(mSurface)`), so the view measured itself to the
    buffer size. Fix: add it with **`MATCH_PARENT`** `RelativeLayout.LayoutParams` — the view stays
    fullscreen and the compositor upscales the smaller buffer to the screen. Verified with
    `dumpsys SurfaceFlinger`: HWC `OVERLAY` source `[0,0,1280,720]` → frame `[0,0,1920,1080]`.
- **Engine cvars** — `config/mobile_gfx.cfg` (in the repo `config/`, copied into the assets), exec'd in
  `main.cpp` right after `autoexec.cfg` inside `#ifdef __ANDROID__` and **while still `INIT_LOAD`** so the
  texture-quality cvars apply cleanly: `waterreflect/waterrefract 0`, `stencilshadow/dynshadow/dynlight 0`,
  `watersubdiv 16`, `fog 384`, `particlesize 60`, `texreduce 1`, `maxtexsize 1024`, `trilinear 0`,
  `maxfps 60`.
- **gl4es knobs** — added to the `constructor(100)` `setenv` block in `main.cpp`: `LIBGL_MIPMAP=3`
  (auto-generate + use mipmaps → fewer texture-cache misses on minification) and `LIBGL_NOERROR=1` (skip
  gl4es' internal `glGetError` checks).

## 11. Release signing

`buildTypes.release` had no `signingConfig`, so `assembleRelease` produced an unsigned (uninstallable) APK.
A self-signed keystore drives the release build (standard for OUYA sideload homebrew):

- `source/android/assaultcube-ouya.keystore` (RSA-2048, alias `assaultcube`) — **gitignored**.
- Credentials in `source/android/keystore.properties` (`storeFile / storePassword / keyAlias / keyPassword`)
  — **gitignored**.
- `app/build.gradle` defines `signingConfigs.release` that loads those properties **if present**, and
  `buildTypes.release.signingConfig` falls back to `signingConfigs.debug` when `keystore.properties` is
  absent — so a fresh clone without the key still builds (just not with the official key).

Recreate the keystore on a new machine:
```powershell
& "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -v -keystore source\android\assaultcube-ouya.keystore `
  -alias assaultcube -keyalg RSA -keysize 2048 -validity 10000 `
  -storepass <pw> -keypass <pw> -dname "CN=AssaultCube OUYA Port, OU=Homebrew, O=cubers.net, C=US"
# then write source/android/keystore.properties with storeFile/storePassword/keyAlias/keyPassword
```
> A signature change vs. an already-installed build requires an uninstall first
> (`adb uninstall net.cubers.assaultcube`), which clears app data and forces one asset re-export on next
> launch (now fast + watchdog-protected, see §9).

## 12. Build & deploy recipe

```powershell
$env:JAVA_HOME = "C:\m64\_jdk11\jdk-11.0.31+11"
Set-Location C:\m64\AssaultCube\source\android
$adb  = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$ouya = "<OUYA-IP>:5555"   # your console's LAN address; `adb connect $ouya` first

# --- debug ---
.\gradlew.bat assembleDebug --no-daemon
& $adb -s $ouya install -r app\build\outputs\apk\debug\app-debug.apk

# --- release (signed; see §11) ---
.\gradlew.bat assembleRelease --no-daemon
& $adb -s $ouya install app\build\outputs\apk\release\app-release.apk

& $adb -s $ouya shell am start -n net.cubers.assaultcube/.LaunchActivity
```
Rebuilding the native deps (one-time) is described in §4; only re-run if a dep changes. **Bump
`versionCode` in `app/build.gradle` whenever an asset/config file changes**, so `AssetExporter` re-exports
it on the device (see §9).

## 13. Status & remaining work

- **Working (playable):** builds (debug + signed release), installs, boots → menu → loads a map → full
  single-player gameplay with the OUYA controller (movement, look, fire, jump, crouch, aim, weapon switching
  all confirmed). gl4es→GLES2 rendering, OpenAL audio. Soft-keyboard text fields validate on Enter/Back
  (§8); the "Please wait…" splash can no longer hang (§9); performance pass renders 720p upscaled to
  fullscreen with the heavy passes disabled (§10).
- **Next — MULTIPLAYER (deferred):** joining online servers isn't working yet. Most likely cause: the
  server list is fetched over **HTTPS, which SSL-fails on this API-16 device** (logcat:
  `SSLHandshakeException` from `LaunchActivity.updateFromMasterserver`), so the Java side already falls back
  to a bundled `config/mobile_serverlist.cfg`. Routes to explore: a plain-HTTP serverlist mirror, a richer
  bundled list, or a direct-connect (`connect <ip>`) path; plus gamepad navigation for the touchui
  server-browser scene.
- **Other remaining:** tune the render-scale to taste (or expose `renderscale` + the gfx profile as in-game
  cvars/JNI instead of hardcoded); the `glReadPixels`-on-depth crosshair caveat (GLES2 can't read depth
  reliably — combat hitscan is engine-side so fine); an OUYA store icon for publication.

## Appendix — files changed

- `source/android/app/build.gradle` (platform 16, NDK, abiFilters, **release signingConfig**),
  `source/android/build.gradle`, `source/android/local.properties`
- `source/android/keystore.properties` + `source/android/assaultcube-ouya.keystore` (**gitignored**),
  `source/android/.gitignore` (keystore excludes)
- `source/android/app/src/main/cpp/CMakeLists.txt` (vorbisfile + hidapi imports/links)
- `source/android/app/src/main/java/net/cubers/assaultcube/LaunchActivity.java` (fromHtml guard, UTF-8,
  timeouts, run-once guard, try/finally, **stall watchdog**)
- `source/android/app/src/main/java/net/cubers/assaultcube/AssetExporter.java` (64 KB copy buffer,
  `sLastProgressMs` progress signal)
- `source/android/app/src/main/java/net/cubers/assaultcube/AssaultCubeActivity.java` (`getLibraries()` order)
- `source/android/app/src/main/java/org/libsdl/app/SDLActivity.java` (**`sRenderMaxHeight` downscale** in
  `surfaceChanged`, **`MATCH_PARENT` surface layout** for fullscreen)
- `source/src/main.cpp` (ES2 context, gl4es env constructor + `LIBGL_MIPMAP`/`LIBGL_NOERROR`,
  `setupandroidbasedir()`, gamepad mapping, `SDL_HINT_RETURN_KEY_HIDES_IME`, exec `config/mobile_gfx.cfg`)
- `source/src/touch/core/textview.h` (`commitedit()` on the text-input falling edge)
- `source/src/stream.cpp` (`absbasedir` runtime resolution + `setupandroidbasedir()`)
- `source/src/menus.cpp` (`gamepadmenuopen()` helper)
- `config/mobile_gfx.cfg` (new — Android performance cvar defaults)
