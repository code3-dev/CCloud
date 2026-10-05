# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

CCloud is a single-module (`:app`) Android streaming client (movies / TV series) written in Kotlin with Jetpack Compose + Material 3 and Media3 ExoPlayer. It ships one APK for phones, tablets and Android TV (registered for both `LAUNCHER` and `LEANBACK_LAUNCHER`). Package / namespace: `com.pira.ccloud`. minSdk 24, target/compileSdk 36.

## Build commands

```bash
./gradlew assembleDebug                 # debug APKs (ABI splits + universal)
./gradlew assembleRelease -x lint       # what CI runs; output: app/build/outputs/apk/release/app-universal-release.apk
./gradlew lint                          # uses app/lint-baseline.xml; abortOnError = false
./gradlew installDebug                  # install on the connected device/emulator
```

- Gradle wrapper is 8.11.1 (AGP 8.9.2, Kotlin 2.0.21). CI uses **JDK 17**. Gradle 8.11 cannot run on JDK 24+ (e.g. a newer Android Studio bundled JBR), so point `JAVA_HOME` at JDK 17–21 when building from the terminal, and set `ANDROID_HOME` if there is no `local.properties`.
- Release signing reads `key.properties` at the repo root (`storePassword`, `keyPassword`, `keyAlias`, `storeFile`); if the keystore file is missing the release build silently falls back to the debug signing config.
- `scripts/fix-gradle-wrapper.sh` / `verify-gradle-wrapper.sh` regenerate/verify `gradle/wrapper/gradle-wrapper.jar.sha256`, which CI validates.
- Releases: pushing a `v*` tag runs `.github/workflows/build-appshare.yml`, which builds and publishes `CCloud-universal.apk` to GitHub Releases. Bump `versionCode`/`versionName` in `app/build.gradle.kts`; the in-app update check (Settings) compares against the GitHub releases API for `code3-dev/CCloud`.

### Tests

JVM unit tests live in `app/src/test` (the sync storage and engine in `com.pira.ccloud.sync`); there are no instrumented tests yet. `./gradlew testDebugUnitTest` runs them, `./gradlew testDebugUnitTest --tests "com.pira.ccloud.sync.LibraryStoreTest"` runs one class, and `./gradlew connectedDebugAndroidTest` would run instrumented tests. `app/src/test/resources/sync-fixtures/` holds cases shared with the Apple app (merge rule, settings mapping); keep both apps passing the same fixtures.

## Architecture

The README's architecture section overstates things: there is no domain layer, no LiveData, no DI (Flows appear only in the sync code, see Persistence and Sync), and the Leanback library is a dependency but the TV UI is pure Compose. What actually exists:

### Two activities
- `MainActivity` hosts the whole Compose app (`MainApp` → `MainScreen` → `AppNavigation`).
- `VideoPlayerActivity` is a separate, landscape, fullscreen activity. Detail screens start it through `components/ResumePlayback.kt` (`playOrAskToResume`), which offers to continue from a saved position; that calls `VideoPlayerActivity.start(context, url, progressKey, startPositionMs)` or `startWithEpisodeInfo(...)` (the episode ids are used to mark the episode as watched). With a `progressKey` (`SyncKeys.movieProgress` / `episodeProgress`; null for trailers, see `TrailerMarkers`) the player saves its position every 10 s, on pause, after a seek and at the end, under the rules in `ProgressPolicy` (nothing before 30 s, finished from 95 %). It embeds a Media3 `PlayerView` with `useController = false` inside `AndroidView`, and draws its **own** Compose control overlay (seek bar, play/pause, speed, track-selection dialog). Remote-control handling is described under "Android TV remote input" below.

### Phone vs TV layout
`DeviceUtils.isTv(context)` (UiModeManager == TELEVISION) is the layout switch: TV renders `SidebarNavigation` (a `NavigationRail`) beside the content; phones render `BottomNavigationBar` in the `Scaffold`. Grid column count (`DeviceUtils.getGridColumns`) and default subtitle size also branch on it. For behaviour that depends on *how* the user navigates (remote vs touch/mouse), check `LocalInputModeManager.current.inputMode == InputMode.Keyboard` instead, as `SearchScreen` does. D-pad navigation relies on Compose's default 2D focus search over `clickable`s and Material controls; see the rules below before adding any focus code.

### Android TV remote input
- **Focus structure (the cause of most TV bugs here):**
  - Only interactive elements may be focus targets. Never put `.focusable()` on a list or on a card that holds other controls: the D-pad stops on the container and can't reach anything inside it.
  - Don't add `.focusable()` next to `clickable` or a Material button. It creates a second, nested focus target, and OK then just moves focus inward instead of clicking.
  - A button inside a `clickable` parent can't be reached with the D-pad, because focus search doesn't enter a focus target's children. Put such buttons beside the clickable area (see `FavoriteItemCard` and the playlist chips); for expandable cards make only the header clickable (see `SettingsScreen`).
  - When the focused element disappears, Android gives focus to the first element at the top left: on TV the sidebar's Movies item, or Back in the player. Opening a screen or going back is handled by `ScreenFocus` (below), but when you remove the focused element within a screen, move focus somewhere sensible yourself.
  - Don't write `onKeyEvent` handlers for OK/Enter clicks: `clickable` already handles them, and `onKeyEvent` receives both key-down and key-up, so such handlers fire twice. Custom key handling (like the settings sliders) must check `KeyEventType.KeyDown`.
- **Screen focus:** every navigation destination is wrapped in `ScreenFocus` (`components/ScreenFocus.kt`, applied in `AppNavigation`). When a screen is shown with a remote, or the user switches from touch or an air mouse to the remote, it moves focus to the `restorableFocus(key)` element that had focus last (so Back returns to the item that was opened), or else to the `initialFocus()` element. It waits until that element exists and has stayed the target for a couple of frames (content may still be loading), and gives up if the user presses a key first (`ScreenFocusHost` in `MainScreen` counts them). In a new screen, mark one `initialFocus` element for each state (a list or grid can be it: its top-left visible item gets focus), and make elements that open other screens `restorableFocus`. The initial element must be composed without scrolling, so it can't sit below the fold of a `LazyColumn` (why `SeriesDetailsContent` is a scrolling `Column`).
- **Focus visuals:** the default ripple focus highlight is too faint on a TV (on a `Card` with `Modifier.clickable` it's practically invisible). Use `Modifier.focusRing` from `components/FocusRing.kt`, placed before the element's clickable; use `focusOutline` when the focused element is a child, e.g. to outline a whole card while its header has focus.
- Compose lists can't be scrolled by dragging with a mouse or air mouse (by design; only the wheel works). With a remote they scroll by following focus.
- **Player:** `VideoPlayerActivity.dispatchKeyEvent` hands every key to the handler that `VideoPlayerScreen` registers (`onKeyHandlerChanged`), before any view sees it, so keys work even when nothing on screen has focus. With no control focused (controls hidden or shown by a tap): OK = play/pause, Left/Right = seek by `VideoPlayerSettings.seekTimeSeconds`, Up/Down = just show the controls; each of these shows the controls with focus on the seek bar, where Left/Right keep seeking and OK keeps toggling. On other controls the arrows move focus and OK clicks. Back is left to the default handling (closes the player). Whenever the remote becomes the input (at start, or after a mouse or touch), focus also moves to the seek bar, and when the retry state swaps the seek bar out, focus moves to play/pause. Otherwise Android's default focus lands on the Back button and OK closes the player.
- When a key handler consumes a key-down, it must also consume the matching key-up: in Compose foundation 1.7, `clickable` fires `onClick` on **any** OK/Enter key-up, so a stray key-up clicks whichever control just received focus (the player tracks this in `consumedKeys`).
- **Text fields:** a Compose `TextField` opens the keyboard as soon as it gains focus. Never wrap it in `.clickable {}`: that adds a separate outer focus target that captures D-pad focus, so the field (and its keyboard) never gets focus. Compose's own D-pad handling inside text fields ignores remotes that don't report themselves as D-pad devices (common on cheap Android TV boxes, e.g. OK sends `KEYCODE_ENTER`); `SearchScreen.handleRemoteKey` covers them.

### Testing on an Android TV emulator
- `uiautomator dump` fails while the UI is animating and leaves the previous dump in place, so delete the old file and retry until a new one is written.
- `adb shell input keyevent` injects from a *virtual* device, which Compose's text-field D-pad handling (and `handleRemoteKey`) ignore. To behave like a real remote, register one with `adb shell uinput -` (JSON "register"/"inject" commands; `KEY_SELECT` maps to DPAD_CENTER, `KEY_ENTER` to ENTER). If you feed it from a FIFO, keep the FIFO open for writing (e.g. a background `sleep 100000 > fifo`) and make sure no reader from an earlier emulator run is still attached; otherwise key presses are silently dropped.
- The Google TV image needs ≥4 GB emulator RAM; at 2 GB the keyboard (Gboard) stalls for seconds and Back presses fall through to the app.
- If the emulator stops resolving hosts (screens show "Unable to resolve host"), restart it with `-dns-server 8.8.8.8`.
- The content CDN (`*.dl150m.info`) may be unreachable outside Iran, so real titles can fail to play. `VideoPlayerActivity` is not exported; to try the player with a public test stream, export it in a scratch copy of the project, never in the repo.

### Navigation
String routes are defined in the `AppScreens` sealed class (`splash` is the start destination and is replaced by `movies`; detail routes are `single_movie/{movieId}`, `single_series/{seriesId}`, `country/{countryId}`). `MainScreen` maps the current route back to an `AppScreens` by prefix to decide whether the bottom bar is shown. The Movies/Series/Search/Country ViewModels are created once in `AppNavigation` (activity-scoped) so their state survives tab switches — don't create them per-destination.

Tabs (`AppScreens.screens`) are opened only with `NavController.navigateToTab` (`navigation/TabNavigation.kt`), from both the TV sidebar and the phone bottom bar. Movies stays at the bottom of the back stack with at most one other tab above it, so Back from a tab goes to Movies and Back from Movies leaves the app. Switching tabs closes the pages opened from the current tab and saves the tab screen's state (scroll position, and the focused item `ScreenFocus` restores), which is restored when the user switches back; choosing the tab the current page belongs to goes back to that tab. Other links, like the Favorites heart in Settings, open a page within the current tab (Back returns to Settings). Don't use `graph.findStartDestination()` for tabs: the start destination is the splash, which is no longer in the back stack.

### Passing items to detail screens
Detail screens do **not** receive objects via nav arguments. The list/search screens first call `StorageUtils.saveMovieToFile` / `saveSeriesToFile` (which deletes previously cached items and writes `filesDir/movie_<id>.json` / `series_<id>.json`), then navigate with only the id; `SingleMovieScreen`/`SingleSeriesScreen` read it back with `loadMovieFromFile`/`loadSeriesFromFile`. Navigating to a detail route without saving first shows "not found".

### Data layer
- Repositories in `data/repository` extend `BaseRepository`, which owns the shared `OkHttpClient`, the API key, and `executeRequest(primaryUrl, requestBuilder)`: on failure it retries the same path against each host in `helperServers`. Always go through `executeRequest` so the fallback hosts work.
- Endpoints are `https://server-hi-speed-iran.info/api/...` with the API key as a path segment. Responses are parsed by hand with `org.json` (`optString`/`optInt` with defaults, skipping malformed items), not kotlinx.serialization.
- ViewModels expose Compose `mutableStateOf` properties with `private set` and launch work in `viewModelScope`. Results are filtered through `LanguageUtils.shouldDisplayTitle` to hide items with Farsi titles.

### Persistence
- `StorageUtils` (a singleton object) is the storage API the screens call. Favorites, favorite groups (playlists), watched episodes, playback positions and the synced settings are **sync records**: it delegates them to `sync/LibraryStore`, which keeps them in `sync/RecordStore` (one `records_<collection>.json` per collection in `filesDir`). Every change gets a timestamp and the device's id, and deletions leave tombstones, so the records can later sync with other devices (the Apple app shares the format; see `SyncRecord` and `SyncKeys`). The built-in default group isn't stored: it always holds every favorite.
- Synced settings are the font, the seek interval and the subtitle text/background colors (only the palette Settings offers, matched by `SettingsMapping`). Their files (`font_settings.json` etc.) still hold every field, including the ones that stay on the device (subtitle size); when loading, a sync record wins over the file. A setting at its default isn't stored as a record until the user changes it.
- `sync/LegacyMigration` moved the pre-sync `favorites.json`, `favorite_groups.json` and `watched_episodes.json` into records once (marker file `sync_migrated_v2`) and renamed them to `*.v1.bak`.
- `StorageUtils.changes(context, collection)` is a `StateFlow` that ticks on every change to a collection; screens that show favorites, watched marks or the font collect it so changes from sync appear without reopening them.
- The detail-screen cache (`movie_<id>.json` / `series_<id>.json`) is still plain files.
- Classes in `data/model` (including `data/model/sync`) are `@Serializable` and are kept by ProGuard (`-keep class com.pira.ccloud.data.model.**`); renaming their fields breaks data already stored on users' devices, and for `data/model/sync` also breaks sync with the Apple app.
- Theme settings are the exception: `ThemeManager` uses SharedPreferences (`theme_prefs`).
- Settings changes flow through callbacks, not shared state: `MainApp` holds theme/font state and passes `onThemeSettingsChanged`/`onFontSettingsChanged` down through `AppNavigation` to `SettingsScreen`. `VideoPlayerActivity` reads settings from `StorageUtils` directly when it starts.

### Sync between devices
- `sync/SyncEngine` keeps the records in sync with the user's other devices (Android and the Apple app) through a `sync/transport/SyncTransport`. There are no accounts: devices form a group by pairing with an 8-character code (`PairingCodes`, shown as `XXXX-XXXX` and as a `ccloud://pair?code=` QR code, 5 minutes, single use). Pairing works both ways: a new device shows a join request that a member approves (`PairingKind.REQUEST`), or a member shows an invite that the new device uses (`INVITE`); TVs have no camera, so the code can always be typed.
- **Hidden in release builds.** `BuildConfig.SYNC_ENABLED` is true only in debug builds, which talk to `FakeSyncTransport`, an in-memory stand-in for the server (a join request is approved by a simulated phone after 3 s; groups vanish when the app restarts, and the stored membership then fails and turns sync off). The camera permission and the `ccloud://pair` link live in `src/debug/AndroidManifest.xml`. Move them to `src/main` when the real server exists.
- `sync/android/SyncService` creates the one engine and connects it to the app's foreground/background (`ProcessLifecycleOwner`) and to network changes. The engine runs on a single-threaded scope; its public functions hand their work to it, so call them from anywhere.
- Timing: favorites, playlists and watched marks are sent 3 s after a change, settings after 10 s, playback positions when the player pauses or closes and every 5 min while playing. It pulls at start, on returning to the foreground, every 15 min in the foreground (not while playing), and before playback (`prepareToPlay`, at most 1.5 s, so a position saved on another device can be offered). Failures retry with backoff (5 s up to 15 min), and at once when the network comes back. A rejected membership (`Unauthorized`) turns sync off and keeps the local data.
- A sync that was in flight when the device left or switched groups must not save its result: `runSync` checks `isStillMember` after every call. Keep that check in any new path that saves `SyncState`.
- The membership (`SyncState`: credentials and cursor) is encrypted with a Keystore key in `sync_state.bin`. It and `sync_device_id` are excluded from backups (`res/xml/backup_rules.xml`, `data_extraction_rules.xml`): a restored copy would give two devices the same id.
- The UI is `ui/sync/SyncScreen` (route `sync?code=`, opened from a Settings card shown only when sync is available): QR codes are drawn and read with ZXing (`QrCode`, `QrScanner` with CameraX), not ML Kit, which needs Google Play services.
- `SyncEngineTest` runs two or more engines against one `FakeSyncTransport` with virtual time; `syncLatencyMs` delays responses to overlap calls.

### Fonts
The optional Vazirmatn font exists twice: `res/font` (used by `FontManager` for Compose text) and `assets/font` (used by the player via `Typeface.createFromAsset` for subtitle styling). Keep both in sync.
