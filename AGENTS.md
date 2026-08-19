# Desktop Fortress Spatial App Handoff

## Runtime contract

- Root: Shared Space `DefaultWindowContainer` (`MainWindow`).
- Gameplay: explicit secondary Mixed `Stage` (`GameStage`).
- Scene strategy: runtime ECS entities in `SpatialView`; calibration controls are a root-level head-following `AttachmentPanel`, independent of the world-locked board transform.
- PICO Spatial SDK: BOM `0.13.3`.
- `SpatialLaunchActivity` is required by SDK 0.13.3 and transitively extends `ComponentActivity`.
- SDK 0.13.3 does not expose a public `SpatialContext` type; `Application.launch(::mainApp)` plus Sense managers is the verified lifecycle mapping.

## Build and verification

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

The canonical spatial-design-to-app gate outputs live under `.scratch/`.
The last clean run passed Gradle build, real-device install/launch,
architecture, 53 JVM tests, and SpatialUI design-style verification.

## Device-only validation remaining

- Real plane/mesh anchor fidelity, camera permission behavior, and hand/controller gestures require a PICO device.
- Do not automate volumetric interactions with `adb shell input tap`.
- Emulator `screencap` may corrupt spatial compositor content; do not use it as a visual oracle.

## Tower-system increment

- `TowerManager` owns atomic place/merge/sell decisions, target selection, attack cadence, and the 20-projectile hard cap.
- `TowerPool` caches by tower type + level; `ProjectilePool` recycles a fixed maximum of 20 runtime projectiles.
- `TowerBalanceTable` contains 4 lines × 5 tiers. No approved planning balance sheet exists in the workspace, so every row is marked `ENGINEERING_DEFAULT_PENDING_DESIGN` and must not be represented as final design data.
- `TowerScene` renders procedural runtime ECS tower silhouettes, drag ghosts, projectiles, merge/hit pulses, and grounding markers. Spatial Editor gateway/backend tools were unavailable in the implementation session, so there is no fabricated editor bundle.
- Editing is allowed only in `PREPARE` and `WAVE_PAUSE`; `FIGHTING` locks purchase, movement, merge, and sale.
- Emulator repair also serialized Sense anchor-map access after a reproduced `ConcurrentModificationException`.

## Monster and level-system increment

- `MonsterManager` owns seven typed `BaseMonster` subclasses, buff-aware smooth path movement, endpoint/ranged-endpoint actions, a per-type object pool, and a hard 15-live-monster cap. A capped spawn returns `null`; `LevelManager` retains that type at the head of its queue and retries later.
- Logical monster Y is always the current desktop height. `MonsterScene` converts world state through board-local coordinates and reuses procedural ECS visuals by stable pool-object ID; no Spatial Editor bundle or authored model resource is claimed.
- `LevelCatalog` contains 20 independently materialized configurations, split into levels 1–5 novice, 6–12 advanced, and 13–20 high difficulty. Levels 5/10/15/20 end in a Boss group.
- `LevelManager` owns the 0.8-second spawn cadence, wave pause/completion, endpoint health, failure, star thresholds, and full-star crystal-core award. Highest stars, fastest times, unlocks, and crystal cores persist through `PreferencesManager`.
- Monster and level numeric balance rows are marked `ENGINEERING_DEFAULT_PENDING_DESIGN`; they are implementation defaults because no approved monster/level balance sheet exists in the workspace.
- The APK was installed and launched on managed PICO emulator `emulator-5554`; package state was running and the AndroidRuntime crash query was empty.

## Economy, progression, save, codex, and achievement increment

- `GoldManager` is the sole in-level currency owner. Level initialization, kills, waves, completion rewards, base-tower purchases, and sales all route through it; negative or overflowing inputs are repaired/clamped.
- `DevelopManager` exposes eight upgrades across economy/offense/defense/reward. Every affected dimension uses `base × (1 + bonusRatio)`; upgrade values/costs are marked engineering defaults pending approved balance.
- `PreferencesManager` owns the version-2 SharedPreferences save, legacy star/crystal migration, sanitization, SHA-256 integrity checksum, and atomic snapshot writes. It persists player, 8 upgrades, 20 level records, tower/monster codex sets, 20 achievements, settings, and achievement counters.
- `CodexManager` unlocks tower tiers on first placement/merge and monsters on first spawn. `AchievementManager` evaluates 20 one-shot growth/challenge/collection achievements and atomically awards crystals.
- `HomePage` exposes built-in SpatialUI `Button`/`Text` navigation for development, paginated codex details, and paginated achievement status/rewards. The `DefaultWindowContainer` retains system `Material.Regular` glass and has no custom root background.
- At that increment checkpoint the suite had 42 JVM tests. The current total after the spatial UI increment is 48. SpatialUI design-style verifier reports 0 errors / 0 warnings.

## Spatial UI panel increment

- `UIManager` owns the single active A-modal, the visible B-HUD set, HMD pose ingestion, modal recentering, obstacle backoff, and HUD smoothing. High-frequency transforms are deliberately separated from the low-frequency visibility flow to avoid per-frame Compose recomposition.
- A panels remain mutually exclusive. Settlement and pause retain the 1.0 m world-lock plus 0.3 s threshold-recenter contract. Calibration is the deliberate exception: its dedicated 0.8 m x 0.6 m root AttachmentPanel stays 1.2 m ahead at gaze-center height and continuously follows yaw/pitch with the same 0.1 s lag as B-HUD, with roll forced to zero and no threshold recentering.
- B panels: combat top HUD and bottom action HUD. Both stay 1.2 m ahead and follow HMD pose with a 0.1 s time constant as root-level AttachmentPanels independent of the board transform.
- `PanelRenderLayer` freezes 10/20/30/40 draw orders at 0/0.001/0.002/0.003 m depth. Shared transparent material semantics are ZWrite off + alpha blend; `FortressPanelSurface` uses local `Material.Thick` glass, token-routed translucent fill, a fixed cyan-blue border, and 28 dp corners.
- `SpatialActionButton` preserves built-in SpatialUI hover/press/haptic behavior for pinch and controller trigger, and adds a non-consuming two-second spatial-pointer dwell path for gaze confirmation.
- Final suite: 48 JVM tests. SpatialUI design-style verifier: 0 errors / 0 warnings. Emulator install/launch passed; the crash buffer remained empty. Actual eye-gaze dwell and hand/controller interaction still require a PICO device.

## Compatibility exception

Latest PICO Spatial SDK 0.13.3 requires `compileSdk 35`, AGP 8.6+, and Kotlin 2.x-compatible metadata. The project retains requested `minSdk 29` and `targetSdk 33`, but uses the generated compatible toolchain (AGP 8.13.2, Kotlin 2.1.20, compileSdk 35) instead of the mutually incompatible Hedgehog/Kotlin 1.9/compileSdk 33 combination.

## Calibration follow increment

- `UIManager` gives `CALIBRATION_GUIDE` its own high-frequency `calibrationTransform`; it never enters the world-lock, obstacle-backoff, or distance-threshold branch used by the remaining Stage modals. It keeps the full three-dimensional gaze anchor through exactly 90 degrees and resets to current front when either horizontal yaw or vertical pitch separation exceeds 90 degrees in either direction.
- The calibration panel remains zero-roll at 1.2 m, uses the shared `HUD_LAG_SECONDS = 0.1f` exponential smoothing constant for translation, and uses the HUD's immediate over-threshold reset policy for both axes.
- `BoardPreviewMode` separates `FOLLOWING_GAZE` from `WORLD_LOCKED`. Before preview lock the board follows horizontal gaze with smoothing while Y is always the desktop height. After preview lock, head updates cannot move it and ray drag/scale become available. Final placement requires preview lock and remains permanently world-fixed.
- Board frame updates are isolated from `GameUiState`, so the head-follow panel does not recompose on every preview transform tick.
- Final suite: 53 JVM tests. SpatialUI design-style verifier: PASS, 0 errors / 0 warnings. The latest debug APK was installed and launched on the real PICO device; on-headset comfort and three-input interaction still require human validation.

## Ground-placement and calibration-UI visibility fix

- The board now targets the detected room ground instead of a tabletop. `PlaneSelector.selectMainGround` rejects horizontal planes above Stage foot-origin Y + 0.35 m, then selects the largest qualified plane in the lowest 0.25 m height band.
- `SpatialManager.getGroundHeight()` and `getMainGroundPlane()` are the preferred APIs. The old desktop-named methods remain compatibility aliases for existing entity code.
- Scan failure now creates a ground fallback at Stage Y = 0 and 0.8 m ahead in Z; it no longer creates a head-relative floating board.
- Calibration `AttachmentPanel` visibility and transforms are applied directly from the Stage frame loop after `GameManager.update`. This fixes the race where `UIManager` entered calibration after Compose's last update and the entity stayed at zero scale.
- “确认放置棋盘” is a dedicated 220 dp primary action in the calibration panel and remains visible while disabled until preview lock.
- Final suite: 55 JVM tests. SpatialUI design-style verifier: PASS, 0 errors / 0 warnings. Latest APK install and process launch passed on real PICO `PB314XHGKC160016G`; crash buffer was empty. On-headset ground alignment and panel visibility still require a human check because the device did not keep `MainWindow` focused during remote verification.

## Level-entry UI and direct-placement fix

- Root cause of “no UI after level selection”: `HomePage` had a composition-time `LaunchedEffect(Unit)` that unconditionally reopened `MAIN_MENU`. When opening Full Space recreated the Planar content, that second UI writer could overwrite `CALIBRATION_GUIDE`, which has no renderer in the Stage. The redundant effect is removed; `GameManager` remains the sole state/UI synchronizer.
- Normal level entry now calls `openStage(GameStage)` first and dispatches `EnterCalibration` only after the open call completes. This also prevents the Planar Activity pause during the space transition from pausing an already-active calibration state.
- `BoardManager.resetForCalibration` now creates a `WORLD_LOCKED` editable preview at the current horizontal gaze position exactly 1.0 m ahead and at ground Y. It can be dragged, scaled, or confirmed immediately; no preview-lock prerequisite remains.
- The calibration panel exposes “重置到前方 1 米”, “缩小”, “放大”, and an always-enabled “确认放置棋盘” action. Confirmation defensively locks any legacy following preview before validating obstacles.
- Ground fallback footprint is 4 m × 4 m so the 1 m direct placement is not clamped back to the old fallback center.
- Final suite: 56 JVM tests. SpatialUI design-style verifier: PASS, 0 errors / 0 warnings. Latest production APK installed and launched on real PICO `PB314XHGKC160016G`, PID 31670; crash buffer was empty.

## Stage forward-axis and underground-plane fix

- PICO Stage uses `-Z` as the direction from the viewer into the scene; SDK `Vector3.FORWARD` is `+Z` and points back toward the viewer. `PicoHeadPoseTracker` now rotates the explicit `LOCAL_STAGE_VIEW_FORWARD = Vector3.BACK`, and `UIManager` uses the same basis for its startup fallback pose.
- The corrected pose feeds both direct board placement (1.0 m ahead) and the calibration AttachmentPanel (1.2 m ahead), so they can no longer be placed 180 degrees behind the HMD by the shared tracker.
- Ground-plane selection now accepts only Stage-relative Y in `[-0.30 m, +0.35 m]`. A stale deep anchor can no longer win the previous "lowest qualified plane" rule and pull the board underground; no valid plane still falls back to Stage Y = 0.
- Final suite: 58 JVM tests. SpatialUI design-style verifier: PASS, 0 errors / 0 warnings. The final APK was streamed-installed and launched on real PICO `PB314XHGKC160016G`; device-side APK hash matched, PID 3403 remained alive, and the cleared crash buffer stayed empty.

## Game-controller increment

- `GameManager` is the only production writer of `GameState`. It validates a finite transition map and owns state entry/exit effects, the Stage frame loop, pause/resume reasons, module start/stop, and key-node logging.
- `LevelManager` no longer mutates global state. It reports wave completion, level completion, and failure through `FlowListener`; monster death still atomically grants developed kill gold and increments persistent kills.
- Lifecycle backgrounding and spatial-tracking loss pause the active Stage. Foregrounding/tracking recovery never auto-resumes. Network loss only emits a local-play-safe message.
- A board outside the horizontal view cone for 5 seconds is recentered on the desktop in front of the HMD. Towers, projectiles, monsters, and active monster paths translate by the same world delta.
- `GameRecoveryStore` persists a checksummed safe checkpoint: level, next/current wave boundary, endpoint health, gold, and tower type/level/cell layout. Restore deliberately requires a fresh desktop calibration before resuming at `WAVE_PAUSE`.
- The bottom B-HUD contains a reusable debug panel for state/FPS/counts/memory plus invincibility, max gold, level skip, and board/tower/monster collision-volume overlays.
- Final suite: 50 JVM tests; design-style verifier: 0 errors / 0 warnings. Emulator `emulator-5554` install/launch passed with focused `MainActivity`, live process, and an empty crash buffer.

## Six-slot tower-placement increment

- The former shop-button drag path was removed because SpatialUI drag offsets are View pixels, while the old code treated the drag start as Stage meters and divided deltas by an arbitrary 1000. Tower drag deltas now use `LocalPhysicalLengthConverter` and explicitly map View +Y-down movement onto the horizontal board's local Z axis.
- `TowerManager.purchaseToSlot` atomically charges a level-1 purchase into the first of six logical slots. Slots store only type/level configuration; pooled ECS tower entities are obtained only after a valid board drop or merge. A race-safe full-slot rollback uses `GoldManager.refundGold` and does not inflate lifetime earned-gold statistics.
- Slot drops and existing-board-tower drops share the same place/merge/invalid/sell transaction core. Invalid drops retain the original slot item, slot long-press uses the platform 0.5-second long-press timeout, and pause/background/fighting transitions cancel the active ghost without consuming the slot.
- `BottomActionHud` remains the existing B-group, 1.2 m, 0.1-second head-following AttachmentPanel. It now renders four purchase buttons plus six red-empty/blue-occupied slots, drag pulse, battle lock, disabled affordances, and 0.01 m inner depth separation. Built-in PICO buttons preserve pinch/controller/gaze confirmation; slot drag is pinch/controller based.
- Runtime tower selection now begins from the target ECS entity identity (`CollisionComponent` + `InteractableComponent`) instead of guessing a tower from an invalid gesture start coordinate. Board highlights are green for placement, blue for merge, and red for invalid targets.
- Safe crash-recovery checkpoints also persist logical slot type/level/index data. The reader remains backward compatible with the previous six-part checkpoint payload.
- Final suite: 68 JVM tests, 0 failures. SpatialUI design-style verifier: PASS, 0 errors / 0 warnings. The debug APK was streamed to real PICO `PB314XHGKC160016G`; pulled-back device APK SHA-256 matched the local artifact, PID 11240 remained alive, and the crash buffer was empty. On-headset hand/controller drag accuracy and head-follow comfort still require a human wearing the device.

## Board-front tower-slot model visibility fix

- Root cause: purchases populated logical `TowerSlotItem` state and a 2D HUD card, but `TowerScene` created ECS visuals only after a board placement. The project contains no USD/GLB/AssetBundle tower assets, so there was no purchased-tower model to display.
- `TowerSlotLayout` is now the single board-local meter-space contract for six near-edge slot centers and the right-side sell zone. This keeps `TowerManager` drag starts, Stage rendering, and sell hit testing aligned.
- `TowerScene` creates six red translucent world-space trays in front of the board. Occupied slots immediately show an opaque typed procedural tower preview. Slot previews and placed towers share `createTowerModel`; the archer silhouette now includes bow limbs, an arrow shaft, and an arrow head.
- Every preview root has `CollisionComponent + InteractableComponent` and maps back to its slot index. `GameScreen` resolves slot entities before placed-tower entities, starts the existing atomic slot transaction, hides the source preview during drag, and restores it automatically on rejection/cancel.
- The sell zone moved from the full-width near edge to the board's right side so it cannot overlap the six world slots.
- Final suite: 70 JVM tests, 0 failures. Spatial workflow architecture/implementation/layout gates pass. SpatialUI design-style verifier: PASS, 0 errors / 0 warnings. Real PICO `PB314XHGKC160016G` install/launch passed, PID 13262 remained alive, crash buffer was empty, and pulled device APK SHA-256 matched local `F0D675A45C247BCDCDF2405A18E50B53E68FBEFC3A13ECF8560675463EE20DDA`.

## Placement smoothness and start-action visibility fix

- Root causes: the board-front implementation prebuilt four typed tower hierarchies per slot (24 total) even when hidden, and every 2D slot ran its own permanent infinite pulse animation. The primary fight action was in the second HUD row while the enlarged panel remained 0.34 m below gaze center, so it could fall outside the lower view edge.
- `TowerScene` now owns at most one typed preview per occupied slot and zero previews for empty slots. Preview entities are created on demand, destroyed when a slot empties, and hidden/reused during drag. Primitive meshes are cached by dimensions; tower collision shape/physics resources are shared.
- The six always-running slot animations are removed. Dragging retains a static blue highlight without per-frame Compose recomposition.
- `BottomActionHud` places a 184 dp `start-fight-button` at the first row's leading edge with label `开始游戏` in PREPARE and `下一波` in WAVE_PAUSE. Purchase buttons share row one; six slot cards own row two. The panel is 1520 x 300 and follows at a raised -0.28 m vertical offset.
- `GameManagerTest` verifies the prepared-level start action reaches FIGHTING. Final suite: 71 JVM tests, 0 failures. SpatialUI style and all workflow structure gates pass. Real PICO `PB314XHGKC160016G` install/launch passed, PID 16491 remained alive, crash buffer was empty, and pulled device APK SHA-256 matched local `6EF404AFDA6D779C442294EC2B91B3853448994F000AB0A8C576B32F7B1A0A8A`.

## Placement crash and side-start control fix

- Real-device crash evidence showed `IllegalArgumentException: You can't construct ModelComponent with a closed MeshResource` at `TowerScene.model`. The preview-destruction path released a cached non-global mesh before the newly placed tower reused it.
- Every cached mesh and every shared material/collision resource in `TowerScene` now calls `toGlobal()` before reuse. `BoardScene` retains ownership and closes these persisted resources during scene teardown, so preview/tower entity destruction can no longer invalidate the shared cache.
- `BottomActionHud` is split into a 730 dp purchase/slot area on the left and a 700 dp action area on the right. `start-fight-button` is a dedicated 280 dp primary action in the right column, separate from all four purchase buttons and six slots.
- Final suite: 71 JVM tests, 0 failures. SpatialUI design-style verifier: PASS, 0 errors / 0 warnings. The APK installed and launched on real PICO `PB314XHGKC160016G`, PID 18464 remained alive after the verification window, the cleared crash buffer stayed empty, and the pulled device APK SHA-256 matched local `8F8618B729D1CA8574F8D94841D8062A8395248F76649CC3063B31A0E2A1B8E1`. The exact volumetric drag-and-drop replay remains a wearer check because CLI 2D input cannot drive Stage gestures.

## Wide bottom HUD and persistent start-action fix

- `bottom_action_hud` is enlarged from 1520 x 300 to 1800 x 340.
- `BottomActionHud` now has three fixed columns: 730 dp purchase/slots, 300 dp dedicated `start-fight-button`, and 680 dp status/debug. The primary action sits close to panel center and cannot be displaced by long status text or expanded debug controls.
- Built-in PICO `Button`, `Switch`, and `Text` remain under the existing `PicoTheme`; root glass, hover, haptic, gaze, and B-HUD head-follow contracts are unchanged.
- Final suite: 71 JVM tests, 0 failures. SpatialUI design-style verifier: PASS, 0 errors / 0 warnings. Real PICO `PB314XHGKC160016G` install/launch passed with PID 19982, the cleared crash buffer stayed empty, and the device APK SHA-256 matched local `5F20C206CFB156D28CB2F1EA69D05EE5231FDA67153E8028C953CB36311B67C2`.

## Height-only ground selection fix

- Root cause of the remaining floor-recognition failure: only anchors explicitly labelled `FLOOR` bypassed the legacy area, normal-angle, flatness, and polygon-boundary gates. Real floor anchors reported as `UNKNOWN` could still be rejected even when their height was correct.
- `PlaneSelector` now accepts any candidate solely by a finite viewer-relative ground height. Semantic labels only rank candidates and cannot reject a correctly positioned but mislabeled floor; area is only a deterministic tie-breaker. Heights outside the foot-origin window remain excluded so a raised table is not promoted to ground.
- `SpatialManager` now uses localized `PlaneAnchor.transform.position.y` as the authoritative ground height. Boundary-vertex mean Y remains diagnostic only and can no longer raise or sink the board.
- Final suite: 97 JVM tests, 0 failures. SpatialUI design-style verifier: PASS, 0 errors / 0 warnings. Debug APK SHA-256: `2406A2EA4AC23AB654FFC58F2CB7B9950FC96AEFA676345C0CD11C45DD2F9AA6`. The APK was streamed-installed and launched on real PICO `PB314XHGKC160016G`; PID `19714` remained alive, the cleared crash buffer was empty, and the pulled-back device APK hash matched the local artifact. Entering a level to validate physical floor height remains a wearer check because CLI input cannot drive Stage interactions.

## Stored-before-pickup purchase fix

- Root cause: `GameViewModel` routed `BuyTower` through `TowerManager.purchaseAndSelect`, which stored the purchase and immediately called `selectInventorySlot`, creating a drag preview and making the new weapon look picked up.
- `BuyTower` now calls `purchaseToSlot` directly. Purchase only charges gold, fills the first empty logical slot, and renders the stationary slot preview; entering the picked-up state requires a separate ray tap on the slot model or its `拿起` button.
- The obsolete auto-select API was removed, the HUD guidance now describes the two-step interaction, and regression tests assert that `dragPreview` remains null after purchase but becomes active after explicit slot selection.
- Final suite: 97 JVM tests, 0 failures. SpatialUI design-style verifier: PASS, 0 errors / 0 warnings. Debug APK SHA-256: `732BBC4A0E5C3A8B681925C2F3AAA8C7EF949B10DBF90926ED289665E6BB2F7D`. The APK was streamed-installed and launched on real PICO `PB314XHGKC160016G`; PID `20843` remained alive, crash/error logs were empty, and the pulled-back device APK hash matched the local artifact. Physical ray interaction remains a wearer check.

## B-HUD ninety-degree recenter threshold

- The combat top HUD and bottom action HUD retain their shared 1.2 m distance and 0.1-second exponential head-follow filter while their centers remain within 90 degrees of the current horizontal gaze direction.
- `UIManager` now computes the center-to-HMD direction on the Stage XZ plane. If the horizontal angle is strictly greater than 90 degrees, the stale HUD transform is reset directly to the current front target instead of being interpolated from behind the player. Exactly 90 degrees remains in the smooth-follow branch.
- Regression coverage verifies both the 180-degree reset for top and bottom HUDs and the non-triggering 90-degree boundary. Calibration UI, A-modal world-lock behavior, and the board transform are unchanged.
- Final suite: 99 JVM tests, 0 failures. SpatialUI design-style verifier: PASS, 0 errors / 0 warnings. Debug APK SHA-256: `029D66D8671A0BDBE50A563934C43C9FB1158F8C362D4697C6C2AE04F9AA233C`. The APK was streamed-installed and launched on real PICO `PB314XHGKC160016G`; PID `21753` remained alive, crash/error logs were empty, and the pulled-back device APK hash matched the local artifact. On-headset comfort during fast turns remains a wearer check.

## Slot-ray hit target and Stage pause lifecycle fix

- Root cause of the apparent dead weapon click: occupied board-front slots exposed only the small procedural tower sphere as an ECS hit target. The much larger red/blue tray had no `CollisionComponent` or `InteractableComponent`, so slightly off-center controller rays fell through to the board. Every tray is now a 0.16 m-high box hit target with `CollisionComponent`, `InteractableComponent`, and `HoverEffectComponent`; both tray and model resolve to the same slot owner. Selected trays turn bright cyan for an unambiguous pickup state.
- Root cause of the pause loop: `MainActivity.onPause()` was treated as application backgrounding, but PICO normally pauses the Planar `MainWindow` Activity when the secondary Mixed `GameStage` takes focus. `GameManager` now tracks the explicit Stage-open contract and ignores that host-only pause. Actual Stage visibility loss is driven by `LocalSpatialContainerStateManager.isOnstage`; it pauses only after the Stage has first been onstage. Returning to the menu clears the Stage latch and stale pause reason before restoring the Planar window.
- Final suite: 89 JVM tests, 0 failures. SpatialUI design-style verifier: PASS, 0 errors / 0 warnings. The debug APK was installed and launched on real PICO `PB314XHGKC160016G`; PID 11069 remained running and the cleared crash buffer was empty. Local APK SHA-256: `9D3ED109808ED8446F09A92CE580F96F3F50DD326E04B5D7495E9B2E4DE58BA4`. Exact controller-ray selection and Stage focus transitions still require a wearer check because CLI 2D input cannot drive volumetric interactions.

## Persistent and visible tower selection fix

- Device evidence showed the slot ray did select successfully (`slot=5 accepted=true state=PREPARE`), but `TowerScene` immediately moved the recognizable source preview to Y=-5 m and replaced it with a small generic cylinder. A second trigger press on the same slot then canceled the selection, making the successful first input appear dead.
- A selected slot now keeps its typed procedural tower visible, lifts it by 0.10 m, scales it to 1.28 times the normal preview scale, and changes the tray to bright cyan. The generic ghost stays hidden until there is an actual board-cell target. Repeated selection of the same slot is idempotent and cannot cancel from controller-trigger bounce; selecting another slot or normal pause/placement transactions still replace or clear it.
- Slot material writes are state-cached instead of repeated every Stage frame, reducing render-thread command traffic while preserving locked/selected/occupied/empty colors.
- Final suite: 89 JVM tests, 0 failures. The debug APK installed and launched on real PICO `PB314XHGKC160016G`; PID 12340 remained alive and the cleared crash buffer was empty. Local APK SHA-256: `C58EF867F6E867DE9DCE986F8005009B73A4A4E65A89BF213D05F6789B18A2B9`. Exact visual pickup feedback still requires a wearer check because CLI input cannot operate Stage entities.

## Ground-scan lifecycle and ray-plane projection fix

- Root cause of persistent ground-scan failure: `MainActivity` started Sense while still in Shared Space, then stopped it from `onPause` during the normal `MainWindow` to `GameStage` transition. `GameManager` is now the sole owner of Sense start/stop, so plane and mesh tracking begins only after explicit Full Space Stage entry and is not cancelled by the Planar Activity pause.
- Ground selection no longer assumes that SpatialView-local Y=0 is Stage floor. Localized HMD height supplies the expected floor band, PICO `SemanticLabelType.FLOOR` has priority, unknown horizontal planes must be within 0.65 m of the expected floor, and non-floor semantics cannot win. Fallback ground is likewise localized to HMD Y minus the 1.6 m engineering eye-height estimate.
- Ground diagnostics now log anchor count, expected Y, selected anchor, semantic, converted height, area, and flatness so an on-headset scan can distinguish no-anchor, transform, and filter failures.
- Root cause of inaccurate tower dragging: `SpatialPointerInfo.position3D` was treated as a final board hit even when the drag source retained pointer capture; device logs showed its raw depth changing sharply without a valid target entity. `BoardRayProjector` now reconstructs a ray from the localized input-device origin through the converted pointer point and intersects the real board surface. The result is invariant to arbitrary pointer depth; direct cell-entity hits remain the first-priority path.
- Final suite: 83 JVM tests, 0 failures. SpatialUI design-style verifier: PASS, 0 errors / 0 warnings. Debug APK installed and launched on real PICO `PB314XHGKC160016G`, PID 25455 remained alive, crash/FATAL queries were empty, and device APK SHA-256 matched local `5A28FB67FF187EECD4028FD804DF787C5E6A1BB054139FCD0691E04574BD15FA`. Exact controller/hand ray placement and physical-floor recognition still require a wearer interaction because CLI input cannot drive a Spatial Stage.

## Stage-ground, 2 m board, and ray-drop fix

- Sense ground anchors are accepted only near Stage floor origin and normalized to logical Y=0. The board render root adds 0.006 m clearance so its bottom face no longer clips into the physical floor; fallback ground is an 8 m square centered on the current viewer X/Z.
- Calibration now places the board exactly 2.0 m along the current horizontal gaze and rotates it so local +Z (the tower-slot near edge) faces the player. Direct placement is no longer shortened by a partial Sense plane footprint.
- `BoardRayProjector` converts `InputDevicePose` from global View space into the local `SpatialView`, rotates the PICO -Z forward ray, and intersects it with the visible board surface. HUD-slot, board-slot, and existing-tower ray drags all use that intersection; the intersected cell highlights immediately and release executes the existing atomic place/merge/reject flow.
- Final suite: 76 JVM tests, 0 failures. SpatialUI design-style verifier: PASS, 0 errors / 0 warnings. Real PICO `PB314XHGKC160016G` install/launch passed with PID 14237, the cleared crash buffer stayed empty, and the pulled device APK SHA-256 matched local `BCD44BC080A03D48DB14EB885D2FDE5E8D9E80A7DB3770C06B8C0D88E988FE1D`.

## Pointer-cell highlight and release-placement fix

- Root cause: `InputDevicePose.rawRotation` describes the device pose, not the rendered pointer ray and not a guaranteed ray-forward axis. The previous code rotated Stage `-Z`, frequently produced no horizontal-board intersection, then immediately invalidated the drag target; this exactly caused selection with no highlighted cell and rejected release.
- `BoardRayProjector` now consumes the SDK's actual `SpatialPointerInfo.position3D` and converts View-local pixels into local `SpatialView` meters. No device-axis inference remains.
- Every board cell now owns a simple shared box `CollisionComponent` plus `InteractableComponent` and maps directly to `CellCoordinate`. The non-consuming low-level pointer listener resolves that entity first, updates the green/blue/red highlight, and the existing drag release commits the same atomic place/merge/reject transaction. Pixel-to-meter drag remains a safe fallback when a pointer-position sample is absent.
- Final suite: 74 JVM tests, 0 failures. SpatialUI design-style verifier: PASS, 0 errors / 0 warnings. Real PICO `PB314XHGKC160016G` install/launch passed with PID 18476, the cleared crash buffer stayed empty, and the pulled device APK SHA-256 matched local `83BD927FB9DD1A5DDC89D17FC027F796D5755CBE6B171DBDEEFCB722C1437BBD`.

## Ground-first placement and anchor-localization fix

- Root causes of the underground board were confirmed: Sense plane and mesh anchors were consumed in tracking-global coordinates without conversion through the `SpatialView` root, and `PlaneSelector` then discarded the sensed height by forcing every accepted plane to Stage `Y=0`.
- `GameScreen` now owns a stable identity coordinate root. Sense anchors and HMD tracking are converted through that same root before plane selection, head-relative placement, obstacle checks, and HUD transforms; the moving board entity is never used as the conversion reference.
- Entering calibration clears stale plane state, hides the board, and starts a fresh scan. The board becomes visible at the sensed floor height only after a qualified real plane succeeds. Scan timeout keeps it hidden; fallback placement requires the explicit `使用地面兜底` action.
- Ground selection preserves localized sensed Y, accepts up to 0.45 m of Stage-origin variance, and still rejects stale deep anchors, tables, small planes, excessive tilt, and excessive unevenness. The visual board retains 0.006 m render clearance above logical ground.
- Final suite: 75 JVM tests, 0 failures. SpatialUI design-style verifier: PASS, 0 errors / 0 warnings. The debug APK was installed and launched on real PICO `PB314XHGKC160016G`, PID 20524 remained alive, the crash buffer was empty, and the pulled device APK SHA-256 matched local `6ECE393DE9BDAC4D6CCB4C4F7C7A25A71D7ED807C325DADAF4E02273BAE83AC4`.

## Obstacle-confirmation and ray-direction fix

- Board confirmation is now player-authoritative: reconstructed mesh obstacles no longer reject `lockPlacement`. Sense obstacles remain available for modal backoff and diagnostics, but floor fragments, feet, furniture overlap, or transient mesh noise cannot block a deliberate board placement.
- Calibration board dragging no longer treats View deltas as world `X/Z`. It maps screen-right onto the current HMD horizontal-right vector and screen-up onto the current HMD forward vector, so drag direction remains correct at every Stage yaw.
- Tower drag has a single input-authority rule. Once a real `SpatialPointerInfo.position3D` or SDK-resolved cell entity is available, that ray target remains authoritative; captured-source gesture pixel deltas cannot overwrite the highlighted cell or reverse the ghost. Leaving the board invalidates the target instead of retaining a stale valid drop.
- Final suite: 78 JVM tests, 0 failures. SpatialUI design-style verifier: PASS, 0 errors / 0 warnings. The APK was installed and launched on real PICO `PB314XHGKC160016G`, PID 23082 remained alive, the crash buffer was empty, and the pulled device APK SHA-256 matched local `2A54484814238F53B0C2D8CD834378AB429DEB914C1FB1F4416FD0D2B23F74EE`.

## Sense startup and exclusive-container fix

- Device inspection confirmed there is one installed package and one process, not two applications. The apparent duplicate was the same process retaining both the Planar `MainWindow` and Mixed `GameStage` spatial containers.
- Selecting a level now opens `GameStage`, verifies `OpenStageResult.Allowed`, minimizes the originating `MainWindow`, and only then enters calibration. Every Stage return path restores `MainWindow` before closing the Stage, so the Planar surface cannot occlude gameplay pointer events.
- Root cause of persistent `anchors=0`: `SpatialManager` called `loadAllAnchors()` immediately after asynchronous `start()`. PICO SDK 0.13.3 returns an empty array unless the manager is already `TrackingState.RUNNING`. The manager now waits up to 5 seconds for RUNNING, retries an empty plane snapshot ten times, logs provider state/count per attempt, and loads mesh anchors only after its provider is RUNNING.
- Recovery now opens/minimizes containers before restoring game state, preventing the Planar Activity pause during the space transition from pausing restored calibration.
- Final suite: 83 JVM tests, 0 failures. SpatialUI design-style verifier: PASS, 0 errors / 0 warnings. Debug APK installed and launched on real PICO `PB314XHGKC160016G`, PID 29242; crash buffer was empty and device APK SHA-256 matched local `A9FA129E7AA4BF8A8469C048356D4C303EADFE7C132626AF8FF77CF122EE98E5`. A wearer must select a level once to confirm the new provider-state/anchor-count logs because CLI 2D taps cannot drive spatial UI.

## Semantic-floor height-only fix

- Real-device diagnostics proved Sense was returning 40–41 anchors and an explicit `FLOOR` at approximately Y=0.48 m with an area of 218.9 m². The floor was incorrectly rejected because its captured boundary variance (about 0.058 m) exceeded the old 0.01 m flatness filter.
- Explicit semantic `FLOOR` anchors are now trusted only as height sources and no longer pass through area, boundary-flatness, or normal-angle rejection. Strict geometry checks remain active solely for unlabeled `UNKNOWN` fallback candidates.
- The selected sensed height is projected onto an 8 m × 8 m logical horizontal surface centered on the viewer. Board placement therefore consumes the real detected Y while ignoring incomplete/noisy room boundaries that could clamp or displace the board.
- Final suite: 84 JVM tests, 0 failures. The debug APK was installed and launched on real PICO `PB314XHGKC160016G`, PID 3194 remained alive, crash/FATAL queries were empty, and the device APK SHA-256 matched local `23C68600505D619521B5B7ECCB9D7BCC73A842648BC397783AF9D5B66D8A47E2`.

## Two-tap exact-cell tower placement fix

- SDK inspection confirmed `detectSpatialPointerEvent` freezes `targetedEntity` at pointer-down for the lifetime of that gesture. A drag beginning on a slot therefore cannot retarget to a board cell; the previous fallback reconstructed a cross-space ray from captured `position3D` and device-pose data, which produced a plausible but displaced board intersection.
- Tower placement now uses two independent pointer cycles: tap/pinch/trigger a HUD or world-space slot to select its logical tower, then point at and tap the target cell. The second event resolves the cell's own ECS entity and commits through `TowerManager.confirmSelectionAtCell`, so no pointer-position projection or pixel-to-meter offset participates in placement.
- Every board cell retains `CollisionComponent + InteractableComponent` and now adds `HoverEffectComponent`, providing direct system hover feedback while aiming. Tapping the selected slot again cancels without consuming the slot item. Existing atomic place/merge/reject logic remains the sole transaction path.
- Final suite: 86 JVM tests, 0 failures. SpatialUI design-style verifier: PASS, 0 errors / 0 warnings. The debug APK was installed and launched on real PICO `PB314XHGKC160016G`, PID 7087 remained alive, crash/FATAL queries were empty, and device APK SHA-256 matched local `D0AB0DC45936D7C475FB4E0B284147F2C61E0ECC688063A658E6BBBFE56890C8`.

## Tower-selection input-consumption fix

- Root cause of the new selection regression: each HUD slot installed a Spatial tap recognizer and a separate Compose long-press recognizer on the same node. Both competed for the same DOWN event, so the long-press path could consume the pointer cycle before `selectInventorySlot` ran.
- Each HUD slot now uses one `detectTapGestures` recognizer owning both `onTap` selection and `onLongPress` sale. The existing `spatialHoverEffect`, disabled alpha, PicoTheme roles, grab audio, and logical slot transaction remain unchanged.
- ECS target resolution for world-space slot previews, placed towers, and board cells now walks up to eight parent entities. A ray hit on a rendered model child therefore resolves to the interactive owner instead of being discarded by exact-identity lookup.
- `TowerPlacementInput` logs slot-selection acceptance, Stage target mapping, and exact-cell confirmation at tap frequency for device-only follow-up diagnostics.
- Final suite: 86 JVM tests, 0 failures. SpatialUI design-style verifier: PASS, 0 errors / 0 warnings. The debug APK was installed and launched on real PICO `PB314XHGKC160016G`, PID 9305 remained alive, crash/FATAL queries were empty, and device APK SHA-256 matched local `3E6780436EDD6879B7D148EC1209EBC3A94F282FF953A3021F7FDFC2640DA8BE`.

## HUD-authoritative tower-selection replacement

- The primary placement flow no longer depends on selecting a procedural ECS tower model. A successful HUD purchase now atomically stores the tower configuration and immediately selects that exact inventory slot.
- Every occupied bottom-HUD slot exposes a built-in PICO `Button` labelled `拿起`; its pinch, controller-trigger, hover, haptic, and gaze behavior comes from the existing `SpatialActionButton` contract. The previous whole-card custom tap/long-press recognizer was removed.
- Placement is now deterministic: purchase or press `拿起`, then use a new spatial pointer cycle to click the target board cell. The SDK-resolved cell entity still commits through the existing atomic place/merge/reject transaction. A selected slot can be sold through the dedicated `出售已拿起武器` button.
- Final suite: 90 JVM tests, 0 failures. SpatialUI design-style verifier: PASS, 0 errors / 0 warnings. The debug APK was installed and launched on real PICO `PB314XHGKC160016G`, PID 13963 remained alive, the crash buffer was empty, and device APK SHA-256 matched local `F1FC62982846A3B3BCF4A40F5E6FDCEDFD25AF1CD45D7C77B1121D6B1F322FFD`.

## Selected-tower, cell-aim, and health-label feedback

- A selected inventory weapon now has a brighter cyan HUD border/fill in addition to the existing `已拿起` label, lifted/scaled procedural model, and cyan world tray.
- While a weapon is selected, every board cell is state-colored from the same place/merge/reject rules: green for an empty placeable cell, blue for a valid same-type/same-level merge, and red for a path, obstacle, max-level, or incompatible tower. Each cell retains `CollisionComponent + InteractableComponent + HoverEffectComponent`, so the native system hover visibly follows the controller/hand ray across cells without consuming the final placement click.
- Repeated samples for the same cell and same drag state no longer republish StateFlows or rewrite materials. The top combat HUD now labels endpoint damage as `血量 current/max` instead of `核心 current/max`; `LevelManager` remains the authoritative damage source.
- Final suite: 91 JVM tests, 0 failures. SpatialUI design-style verifier: PASS, 0 errors / 0 warnings. The debug APK was installed and launched on real PICO `PB314XHGKC160016G`, PID 15664 remained alive, the crash buffer was empty, and device APK SHA-256 matched local `1DC8EC6813AA2AC7B3CB0AF95D87D723BEB1BA22A07AFBA1C1140C2519590C76`.

## Board-tower sale and stale-ring cleanup

- `出售已拿起武器` now routes through `TowerManager.sellSelectedWeapon`, so it sells either a selected inventory-slot item or a selected tower already placed on the board. Board sale clears the cell, recycles the tower, refunds gold, clears drag state, and plays the existing sale feedback.
- Root cause of the persistent circle: placement/merge/sale torus effects were advanced only through `TowerManager.update`, while `GameManager` called that update only during `FIGHTING`. Editable-phase placement rings therefore never reached their expiry time. `GameManager` now advances `TowerManager` in `PREPARE`, `FIGHTING`, and `WAVE_PAUSE`; attack logic remains internally gated to `FIGHTING`.
- Picking up an existing board tower now immediately clears the current cell highlight and any transient effect within 0.10 m of its world position. Successful moves still atomically clear the original board cell before assigning the destination.
- Final suite: 95 JVM tests, 0 failures. SpatialUI design-style verifier: PASS, 0 errors / 0 warnings. The debug APK was installed and launched on real PICO `PB314XHGKC160016G`, PID 17510 remained alive, the crash buffer was empty, and device APK SHA-256 matched local `A0B08860DA361F2FCAEC2D23848DEAC37F0D2A06DEC5C5D3B35317E0A91CFFF7`.

## Symmetric B-HUD yaw dead-zone

- The B-HUD now keeps a horizontal anchor direction while the player turns within `[-90 degrees, +90 degrees]`. Body translation still follows with the existing 0.1-second smoothing, but small or gradual yaw changes no longer drag the panel continuously around the player.
- Signed XZ-plane yaw is evaluated in both directions. A left or right turn strictly beyond 90 degrees resets the top and bottom HUDs together to the player's current forward direction; exactly 90 degrees remains inside the dead-zone. Pitch/roll behavior and all A-modal/calibration contracts are unchanged.
- Final suite: 101 JVM tests, 0 failures. SpatialUI design-style verifier: PASS, 0 errors / 0 warnings. The debug APK was installed and launched on real PICO `PB314XHGKC160016G`, PID 22980 remained alive, crash/FATAL queries were empty, and the pulled device APK SHA-256 matched local `9C8F77D5ADFBFD1C7163275F76445A9BC38FF3210ED52F38908FFC982CB2C20F`.

## Rigid-progression balance profile

- Tower tiers remain the four implemented lines x five levels. Damage, attack speed, and range now compound by 15%/5%/3% per tier (level-five DPS ratio 2.126x); tier traits and non-archer costs are retained. The explicit level-one economy requires the archer cost to be 50 so 78 starting gold leaves 28.
- `LevelCatalog` now owns the five-wave level-one composition, 20-level compounded health/speed curve, 78-to-268 initial gold curve, 5/10 wave gold, 0.52/0.55-second spawn cadence, 20/28 live cap, 1.5-second inter-wave configuration value, and three stage-specific star thresholds. The existing player-triggered `WAVE_PAUSE`/next-wave interaction remains unchanged; the 1.5-second value is not an automatic wave-start timer.
- Level one uses the dedicated 92-health, 0.98 m/s, one-endpoint-damage, two-gold small bug. Standard monsters use the requested 1.8x base-health table and reduced rewards; exploding/acid endpoint output is fixed at 15 and 3 per second. Boss base health is 2,000/5,000/9,000/15,000 before the corresponding level multiplier.
- Endpoint base health is seven. Development ratios are 5/3/2/1/3/3/1/5 percent per level for starting gold, kill gold, damage, attack speed, range, health, wave healing, and sale refund. Legacy `WAVE_GOLD` and `CRYSTAL_REWARD` enum/save keys are intentionally retained while their UI/gameplay meaning is wave healing and sale refund, preserving existing save compatibility.
- Slow sources multiply but are capped at 40% total reduction; same-source slows keep their strongest value. Freeze/stun repeat attempts inside eight seconds pass a deterministic 50% gate, and tower-applied control durations are 20% shorter. Sale refund is 60% in levels 1-3, 30% later, plus 5 percentage points per development level capped at 80%.
- The requested 30% level-one pass rate is not attainable with the exact supplied values in the current deterministic 1.8 m board path. Even granting a level-one archer impossible full-path range, it can fire at most three times (54 damage) before a 92-health bug exits; it cannot earn the kill gold needed for a second tower. The project also has no fifth/sixth tower implementation. These are explicit balance-input gaps, not represented as verified outcomes.
- Final suite: 115 JVM tests, 0 failures. SpatialUI design-style verifier: PASS, 0 errors / 0 warnings. The debug APK was installed and launched on real PICO `PB314XHGKC160016G`, PID 25658 remained alive, crash/FATAL queries were empty, and the pulled device APK SHA-256 matched local `48B4F1D21BDB794DB5FEC05AB0DA1FD4334CA9EBEE769F1A0290389A2A9A8668`.

## Smooth-progression balance profile (supersedes rigid progression)

- The rigid-progression values above are superseded. The four implemented tower lines retain the pre-rigid level-one stats, costs, tier-three/tier-five traits, and splash rows; tier damage/attack-speed/range compound by 15%/5%/3%, producing a 2.126x level-five DPS ratio. Archer level-one cost is restored to 100.
- The seven pre-rigid monster health/speed/ordinary endpoint templates are restored. Kill rewards are rounded from the pre-rigid table at about 60% (5/6/12/11/15/36/120); exploding endpoint damage remains 15 and acid attacks for three damage on a one-second cycle. Boss base health is 2,000/5,000/9,000/15,000 before level multipliers.
- The original 20-level wave counts and unlock cadence are restored. Health compounds by 18% in levels 1-3, 16% in 4-12, and 12% in 13-20; speed compounds by 4% through level 12 and 3% thereafter. Every level uses a 0.6-second spawn cadence, 20-live-monster cap, 20 wave gold, and starting gold `150 + 15 x (level - 1)` (435 at level 20). Boss final waves retain their small-bug group.
- Base endpoint health is 10. A level-one small bug retains five endpoint damage, so two unblocked bugs fail the session. The three stage-specific star thresholds remain 80/95%, 60/85%, and 40/70%. Wave healing remains 5% plus its persisted development bonus, capped at 10%. Sale refund is 70% for levels 1-3 and 40% later, plus five points per persisted development level, capped at 90%. Existing star saves are not rewritten.
- Slow sources multiply, same-source slows keep the strongest value, and total slow is capped at 60%. Repeat freeze/stun attempts inside three seconds use the existing deterministic 50% gate. The current project has no separate burn tower or ballista slow implementation, so no new tower behavior was fabricated.
- Headless gameplay scenarios validate both sides of the level-one boundary: no towers fail during wave one, while deliberate high-coverage placement plus a second archer when earned gold permits completes with positive health. Higher-level composition gates remain a wearer/balance-test target. With binary merge consuming two towers while each tier gains only 1.2075x DPS, two level-one towers have more raw DPS than one level-two tower; strict “level-two required” or “two level-three required” gates cannot be proven without changing merge economics, tower growth, or encounter geometry.
- Final suite: 124 JVM tests, 0 failures. SpatialUI design-style verifier: PASS, 0 errors / 0 warnings. The debug APK was installed and launched on real PICO `PB314XHGKC160016G`, PID 31294 remained alive, the crash buffer was empty, and the pulled device APK SHA-256 matched local `6B29A8C843FE390022E696FA931DC6B2E7EE57B625BB51A3207E8A37D93A19AB`.

## Application icon increment

- The launcher icon is an original generated blue-and-gold tabletop fortress mark that communicates tower defense and merge progression without text. The 1,254 x 1,254 source is retained at `artwork/desktop-fortress-app-icon-source.png`.
- The generated artwork replaces both `mipmap-anydpi/ic_spatial_launcher.png` and `drawable/ic_launcher_foreground.png` with 512 x 512 high-quality PNGs. `AndroidManifest.xml` continues to reference `@mipmap/ic_spatial_launcher`, so no application/container behavior changed.
- `testDebugUnitTest assembleDebug` passes with 124 JVM tests and no failures. Debug APK SHA-256: `86EF8915A72F80CCFEE1E93637BAD4FF1F17722799B39061AA9FA27618ED74BE`.
- The debug APK was streamed-installed and launched on real PICO `PB314XHGKC160016G`; PID `16394` remained alive, the cleared crash buffer contained no app crash, and the pulled device APK SHA-256 matched local `86EF8915A72F80CCFEE1E93637BAD4FF1F17722799B39061AA9FA27618ED74BE`. The unrelated Spatial Editor/project-context doctor findings were left unchanged because they do not block device deployment.

## PICO layered application-icon fix

- Root cause of the unchanged launcher icon: the Android manifest icon and splash bitmap had been replaced, but the manifest's PICO-specific `icon.3d.list` and `icon.sdf.list` still referenced the generated template's white-circle and blue/purple-card assets. PICO OS prioritizes those spatial layered resources on its launcher.
- `icon_3d_layer_0/1.png` now provide a 1,024 x 1,024 opaque-inscribed-circle blue tabletop background and a transparent blue/gold fortress foreground. Both SDF resources were regenerated from the matching alpha silhouettes. The Android adaptive icon now has explicit background/foreground layers, while the splash screen retains the single-layer compatibility artwork.
- The application manifest now routes normal and round launcher entry points through `ic_launcher` and `ic_launcher_round`. Version code/name are `2` / `1.0.1` so launcher icon caches see an application update.
- A clean `testDebugUnitTest assembleDebug` run passes with 124 JVM tests and no failures. Packaged-resource inspection confirms all four PICO layered resources are 1,024 x 1,024 RGBA assets and the APK manifest reports the new adaptive icon plus both PICO metadata lists. Debug APK SHA-256: `77510CFAB4660D27663C5EFF89DF4BA7DBC35B7BC0D17CAEE3F5B111BE67EE35`.
- Version `1.0.1` (`versionCode 2`) was streamed-installed and launched on real PICO `PB314XHGKC160016G`; PID `20597` remained alive, the cleared crash buffer contained no app crash, and the pulled device APK SHA-256 matched the local build exactly.
