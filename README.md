# Create Maglev

A Minecraft 1.20.1 Fabric addon for Create and Create: New Age: build an electrical industry, upgrade ordinary trains into wheeled high-speed trains, then build a dedicated magnetic-levitation railway.

This extends Create's actual train system. You still build the carriage body, assemble it at a station, and use Create's controls, schedules, and railway graph. The addon supplies propulsion bogeys, guideways, manufacturing parts, and electrical traction. It is not a separate vehicle system or a prebuilt train body.

**New to the mod?** Read the [wiki](https://github.com/betizzel/create-maglev/wiki): how trains are powered, a first electric train step by step, every recipe and setting, and fixes for common problems. Its pages live in [`docs/wiki`](docs/wiki/Home.md); after changing them, run `python3 tools/publish_wiki.py` to update the GitHub Wiki.

## License and attribution

Original Create Maglev code, models, textures, and documentation are licensed under the [MIT License](LICENSE), copyright 2026 betizzel. This is an original addon, not an upstream Create or Create: New Age source port.

Create, Create: New Age, Minecraft, and other dependencies remain separate works under their respective licenses. Some addon models reference Create's models and textures by resource namespace; those references require Create at runtime and do not include or relicense its assets. The test harness generates fixture overrides from the locally resolved Create JAR for testing only; those upstream-derived fixtures are not part of the addon source or production JAR.

## Tested target

| Component | Version |
| --- | --- |
| Minecraft | 1.20.1 |
| Fabric Loader | 0.19.5 |
| Java runtime | 17 |
| Create Fabric | 6.0.8.1+build.1744-mc1.20.1 |
| Create: New Age | 1.2.0+mc1.20.1, Fabric release `R4ZMqkxF` |
| Fabric API | 0.92.6+1.20.1 |
| Forge Config API Port | 8.0.2, Fabric |

New Age supplies ESL 1.1.3 and its energy bridge as nested dependencies. The tested runtime does not require a separate Botarium installation.

Compatibility with an arbitrary existing modpack is not implied. In particular, older Create 0.5.x addons may prevent upgrading a pack to this Create version. The launcher instance and user worlds were not modified during development.

## Install

1. Clone your launcher instance and back up its worlds before changing mods.
2. Match the tested Fabric/Create/New Age versions above; do not blindly overlay the dependency pack onto an existing pack.
3. Download `create-maglev-1.1.0+mc1.20.1.jar` from the [1.1.0 release](https://github.com/betizzel/create-maglev/releases/tag/v1.1.0), or build it yourself (see [Build](#build)), and put it into the cloned instance's `mods` directory, replacing the previous Create Maglev JAR rather than installing both versions. Multiplayer requires the addon and its dependencies on both the client and server.
4. Start a disposable creative world first. The **Create Maglev** creative tab contains the bogeys, tracks, substation, and parts.

`build/production-pack/mods/` contains the five-mod runtime set, including the addon, with exact sizes and SHA-256 hashes in `build/production-pack/manifest.json`. Nested upstream libraries remain inside their upstream JARs. Do not install the test-helper JAR from `testing/build/libs`, development JARs, or sources JARs into your normal instance.

## Progression

These are rated straight-line automatic speeds at 20 ticks/second, assuming one block is one metre. Create's native manual throttle modifier, curves, mixed consists, power supply, and braking can reduce actual speed. The addon tiers' numbers are server settings (see [Configuration](#configuration)).

| Stage | Running gear and railway | Rated speed | Idle energy per driven bogey/tick |
| --- | --- | --- | --- |
| Workshop | Existing Create bogeys and track | Existing Create configuration, unchanged | Native Create behaviour |
| Electric | Electric bogeys on standard or reinforced track | 40 blocks/s, about 144 km/h | 8 ESL units |
| Bullet | Bullet bogeys; reinforced track unlocks the higher cap | 60 blocks/s, about 216 km/h; ordinary track caps at 40 blocks/s | 16 ESL units |
| Maglev | Maglev bogeys on the dedicated maglev guideway | 80 blocks/s, about 288 km/h | 32 ESL units |

Curve speed depends on the curve's tightest radius: addon-powered trains may take a curve of radius *r* blocks at √(25 × *r*) blocks/s, and never slower than 20 blocks/s. A 16-block radius therefore allows 20 blocks/s, 64 blocks allows 40, 144 allows 60, and 256 allows a maglev's full 80 blocks/s. Trains brake ahead of a curve to its limit, so wide sweeping curves keep fast lines fast. A mixed wheeled consist uses its lowest bogey tier; stock bogeys therefore limit an upgraded consist. Do not mix wheeled and maglev bogeys: their track requirements are incompatible. Tier upgrades require crafting, not free wrench cycling.

### Build the factory

- Make the addon's steel ingots by heated mixing, and press them into steel sheets. They are tagged `c:steel_ingots` and `c:steel_plates`, and every recipe that uses steel sheets accepts any `c:steel_plates` item, so another mod's steel plates (for example Epic Knights in NUKES Homestead) work too. Laminated cores, wound coils, traction motors, and control units provide the intermediate manufacturing chain.
- **Electric bogey:** sequenced assembly starts with a Create railway casing and deploys a steel sheet, traction motor, and control unit.
- **Bullet bogey:** upgrade an electric bogey with a steel sheet, a New Age overcharged iron sheet, and a control unit.
- **Levitation module:** craft an unenergized module from its constituent parts, then process that single item in a real New Age energiser using **4096 energy**. The material cost is paid before energising; electricity alone cannot create the module.
- **Maglev bogey:** upgrade a bullet bogey using a levitation module, an overcharged iron sheet, and a wound coil.
- **Maglev guideway:** surround one levitation module with eight reinforced tracks to upgrade them into eight pieces of guideway. Build the dedicated guideway rather than trying to float on ordinary rails.

The exact recipes are in [`src/main/resources/data/create_maglev/recipes`](src/main/resources/data/create_maglev/recipes). The carriage's appearance is yours to build with Create blocks: a wheeled bullet train and a maglev can have very different bodies without changing the railway controls.

## Electrical traction

Use New Age's actual generator, magnets, carbon brushes, electrical connectors, and copper wire to charge a **Traction Substation**. Attach a New Age connector to the substation and wire it into the generating network; a shaft alone does not charge the substation.

- Each substation stores **65,536 energy units**, delivers at most **512 units per tick** in total to the trains it powers, and supplies a **64-block spherical radius**, including height. Put it comfortably inside the route's coverage rather than right on a diagonal range boundary.
- Every driven bogey's pickups must be covered by enabled substations. Substations are tracked per dimension and keep supplying while their chunks are unloaded: Create keeps simulating unattended trains in unloaded chunks, and their traction now keeps working there too. Nothing is force-loaded.
- A pickup draws from the nearest covering substation first and from farther ones when that one is empty or at its output limit, so overlapping coverage adds both redundancy and capacity.
- **Weak supply slows a train instead of stopping it.** When substations deliver less than a train asks for, its acceleration scales down and its speed settles where the delivered energy covers its draw. Only supply below the train's idle draw cuts traction, which brakes the train and lands a maglev.
- Right-click a substation with an empty hand, or look at it wearing Engineer's Goggles, to see its charge, breaker state, output rating, and range. A comparator reads its charge level.
- Redstone input opens the traction breaker, which isolates the substation from both trains and charging. It does not erase the buffer. Merely cutting a charging wire leaves stored energy available until it is used.
- Powered bogeys consume electricity even at rest. Their extra running cost rises with speed squared: `idle + ceil(speed² × coefficient)`, where speed is blocks/tick and the coefficient is 4, 6, or 8 for electric, bullet, or maglev respectively. A two-bogey consist at its full tier cap consequently needs 48, 140, or 320 units/tick; size generation, buffering, and substation spacing accordingly.
- There is no hidden coal fallback for upgraded consists; all-stock Create trains remain native.

The route preview looks ahead for curves, unsupported track, unpowered track, and track ends. A loss of traction produces controlled braking; a maglev carriage lowers onto its guideway rather than disappearing or retaining a powered hover after reload. The whole carriage entity is lifted, not just a decorative bogey model.

## Configuration

Balance values are a server config, `config/create_maglev-server.toml` in the game or server directory (Forge Config API Port's global server-config location). The server's values are synced to clients.

| Setting | Default |
| --- | --- |
| `tiers.electric`: `topSpeed`, `acceleration`, `idleEnergyPerBogey`, `speedEnergyCoefficient` | 40 blocks/s, 5 blocks/s², 8, 4 |
| `tiers.bullet`: same keys | 60 blocks/s, 6 blocks/s², 16, 6 |
| `tiers.maglev`: same keys | 80 blocks/s, 8 blocks/s², 32, 8 |
| `tiers.bulletSpeedOnOrdinaryTrack` | 40 blocks/s |
| `track.curveLateralAcceleration` | 25 blocks/s² |
| `track.minimumCurveSpeed` | 20 blocks/s |
| `track.emergencyBraking` | 16 blocks/s² |
| `substations.capacity` | 65,536 |
| `substations.maxOutputPerTick` | 512 |
| `substations.supplyRadius` | 64 blocks |

## In-game help and feedback

- **Ponder:** hold the Ponder key over a substation, bogey, reinforced track, guideway, or levitation module for scenes on substation supply, levitation versus propulsion, and the wheeled tiers.
- **Advancements:** a *Create: Maglev* tab follows the progression from steel to the maglev guideway, ending with riding a fully levitated maglev.
- **Sound and particles:** a levitating maglev hums instead of playing Create's wheel-on-rail loops, and none of the addon's electric tiers chuff steam or release a steam hiss on arrival.

## What is semi-realistic?

A real maglev needs three jobs: **levitation**, **guidance**, and **propulsion**. Magnets provide lift and guidance; a changing magnetic field can act as a linear motor along the railway. Most real bullet trains still use wheels, which is why wheeled high-speed rail is a separate stage here.

Here, Create's graph provides guidance, New Age provides finite electrical energy, and the maglev running gear provides a readable **0.25-block hover**. The energy curve and clearance are gameplay choices, not a calibrated simulation of a particular real-world magnet, air gap, or aerodynamic power requirement. Longer, faster lines need infrastructure, not just a faster-looking carriage.

## Build

The build uses Gradle 8.14.3/Loom 1.12.7 on JDK 21 and compiles Java 17 bytecode. Set `JAVA21_HOME` (or `JAVA_HOME`) and `JAVA17_HOME` to complete JDK 21 and JDK 17 installations.

```sh
./build.sh :build :packageRuntime --console=plain
```

The distributable is the remapped, non-sources JAR in `build/libs`. The production pack is a reproducible local testing/install input, not a licence grant to redistribute third-party mods.

## Native verification

The harness follows the production-regression approach from the sibling `mc_curseforge_fabric_port` repository, kept separate in `testing/`:

```sh
./build.sh :testing:runServerGameTests -PtestEula=true --console=plain
python3 testing/record_tests.py --accept-eula
python3 testing/restart_tests.py --accept-eula
```

Only pass the EULA flags if you agree to the Minecraft EULA. These commands create disposable worlds below `testing/build/run`; they do not use launcher worlds. For client recording, install `testing/requirements.txt` into your Python environment and provide FFmpeg, FFprobe, Xvfb, and xauth. Recording uses a project-owned `work/client-cache`, a private Xvfb display, and silent FFmpeg capture. `--minecraft-home` can select another installation cache.

### Checks and evidence

- The unfiltered server batch contains **61 native Create tests and 16 addon tests**. Addon cases exercise native station assembly and destination arrival, real New Age generation and copper-wire delivery, finite shared-supply exhaustion, native energising and its unpowered counterfactual, live curve traversal, incompatible-track rejection, carriage lift/persistence, survival pickaxe mining of every addon block, an unattended train that keeps running through unloaded chunks on a substation in an unloaded chunk, an empty nearest substation not blocking a charged one, a weakly rated substation capping speed instead of stopping the train, rated top speed for every tier (and the bullet cap on ordinary track), and a radius-64 curve taken at its √(a·r) limit after braking from top speed.
- Native Create's code and assertions are untouched. The isolated helper provides two fixture-data corrections inherited from the ports workflow: a Forge/convention-tag mismatch and Forge-versus-Fabric fluid units. The helper also seeds Create's recipe randomness for the batch. Those overrides do not ship in the addon.
- The helper pins substation capacity to 8,192 (the 1.0.x size) for every harness run, so recorded scenes keep measuring debits from a full buffer; the weak-supply batch temporarily rates substations at 20 units/tick. Train-running addon tests that are new in 1.1.0 run in their own batches, leaving Create's seeded default batch unchanged.
- Seven client scenes cover stock trains, real keyboard-driven electric trains, reinforced-track bullet trains, powered maglev movement, an unpowered maglev keyboard-drive attempt, a real traction-breaker outage with braking and landing, and a seated maglev passenger through the full rise/drive/brake/land cycle. Server fixture assembly and automatic schedules are explicitly labelled; manual scenes use actual OS-level W input. Automatic scenes use a labelled static side camera on a real off-track perch.
- Positive charge comes from native generators and wire, not direct buffer writes. Motion comes from Create controls or schedules, not injected train velocity. Levitation assertions compare the real carriage position with its native track anchor, rather than trusting a reported lift value alone.
- The passenger scene boards a real assembled Create seat through native interaction. It checks the live player and carriage by UUID, the passenger-to-vehicle relationship, Create's seat mapping and control ownership, actual body/player displacement, and stable retention after landing. It does not teleport the rider along the route.
- The restart runner saves a moving, raised maglev with its generator stopped, wire disconnected, and real redstone breaker open, then reopens that same world in a second dedicated-server JVM. Before native ticking it checks the persisted train/graph UUIDs, schedule, style, track pose, speed, lift, breaker, and exact stored energy. The owned world's spawn is set near the fixture so vanilla preloads entity-ticking chunks before the first post-reload train tick. No train or power fixture is rebuilt, no ticks are frozen, and no body state is injected. The live body must still be moving and raised when first resolved, then provide intermediate braking and physical descent samples before landing; a carriage that appears only after settling cannot pass. Finally, closing the real breaker must resume the saved schedule using retained charge.

### Verified checkout results

| Verification | Observed result |
| --- | --- |
| Required native server GameTests | 77 passed, 0 failed (61 Create, 16 addon) |
| Actual-client scenarios | 7 passed, 0 failed |
| Unpowered maglev, real W attempt | No movement and no physical lift |
| Traction-breaker outage | 14 intermediate braking samples, then zero speed and zero physical lift; stored charge retained |
| Seated maglev passenger | Retained native seat/control ownership through 0.25-block lift, keyboard driving, braking, and stable landing |
| Fresh-JVM disk restart | 20 live-body samples, including 8 braking and 18 descent steps; zero final speed/physical lift |
| Restart energy persistence | Exactly 8124 units retained while isolated; closing the breaker resumed the saved schedule and consumed 526 units |
| Rated top speeds | Electric, bullet, and maglev trains each reached at least 99% of 40, 60, and 80 blocks/s and never exceeded them; bullet bogeys on ordinary track held at 40 blocks/s |
| Radius-64 curve at speed | A bullet train braked ahead of the curve and held its √(a·r) limit of 40 blocks/s there, running at no less than 95% of it, twice the old flat cap |
| Unloaded-chunk supply | An unattended scheduled train arrived on charge drawn from a substation whose chunk stayed unloaded (1.0.1 only consulted substations in loaded chunks) |
| Empty nearest substation | A farther charged substation powered the trip; the empty one stayed at 0; fails on 1.0.1 |
| Weak supply | A substation rated at 20 units/tick never delivered more, and the train ran at its sustainable speed instead of stopping |
| Ponder and goggles | All three scenes and the goggles readout captured in a real client and reviewed |
| Distributable audit | Java 17 bytecode (43 classes), valid JSON/PNG resources, no test-helper entries |

For 1.1.0, frames from the maglev and electric recordings were reviewed for the new particle styling (no wheel sparks or smoke; recordings are silent, so the new sounds are not covered by video), and every Ponder and goggles screenshot was inspected. The 1.0.1 recordings remain under `testing/build/reports/recorded-1.0.1/`, and failing-before evidence for the new regressions is under `testing/build/reports/before-review-fixes/`. The archive inspection is recorded in `testing/build/reports/production-artifact-audit-1.1.0.json`. The previously accepted 1.0.0 JAR, recordings, reports, and hashes remain preserved under `testing/build/reports/baseline-1.0.0/`; failed-before restart evidence is also retained separately from the final passing report.


Outputs:

- `testing/build/reports/server-gametests.xml`
- `testing/build/test-runtime.json`, including exact packaged input hashes and fixture overrides
- `testing/build/reports/recorded/report.json`
- `testing/build/reports/recorded/*.mp4`, `server.log`, `client.log`, and client events
- `testing/build/reports/restart/report.json`, phase results, checkpoint, input hashes, and separate server logs

The seven-scene recording report checks encoding and native gameplay outcomes; encoded-video checks alone are not visual review. That original isolated runtime does not certify additional shader packs, arbitrary train addons, or the complete NUKES Homestead pack. The separate full-pack passenger walkthrough below has its own explicitly scoped Homestead result.

### Full-sized moving passenger-car demonstration

`testing/walkthrough_demo.py` records a separate test-only mock-up: a five-block-wide, nineteen-block-long native Create carriage with an enclosed roof and walls, windows, ten seats, cab controls and conductor, lighting, an operable side door, a visible entrance-vestibule bulkhead, and a boarding platform. The body is assembled by a real Create station; it is not a render-only vehicle or a new prebuilt-body item in the gameplay mod.

This runner requires prepared, world-free Homestead server/client templates at `testing/build/homestead-compat/server-template` and `client-template`, plus the installed Fabric client cache used above. Alternative prepared directories can be supplied with `--server-template` and `--client-template`. It refuses to overwrite an earlier run or copy a template containing worlds.

```sh
./build.sh :testing:prepareTestRuntime -PtestEula=true --console=plain
python3 testing/walkthrough_demo.py --accept-eula --run-name moving-passenger-demo
```

On the tested Linux software-Xvfb setup, select the compatible installed GLFW with `MAGLEV_GLFW_LIBRARY=/usr/lib/libglfw.so.3` and set `__GLX_VENDOR_LIBRARY_NAME=mesa`. These are isolated test-runtime overrides, not required changes to the user's pack. The pack's shader setting is retained.

The client completes Homestead's welcome and MCA setup through normal mouse input, opens the train door, and boards using real W input. After boarding, an actual Create schedule starts the powered train. The client waits for verified physical carriage motion, watches fixed trackside posts pass the windows, then walks down the aisle and back while the train is still moving. Read-only carriage-local coordinates tell the input driver when to release W; they never set the player's position or velocity. The client's interpolated carriage trails the server's (1.6–2.8 blocks observed at service speed), so the server places the player that much nearer the front of the carriage than the client does; the driver therefore stops near the rear wall, and early on the way back, so the server-side checkpoint regions still hold. The player stays aboard rather than exiting a moving train. Only the initial exterior/doorway positions and later view angles are staged.

Five real NewAge generators and copper wires charge the traction buffers before assembly. Redstone then isolates the parked train until boarding finishes; departure stops the generators, disconnects the wires, enables the charged buffers, and sets an ordinary 0.1 service throttle. Native checkpoints require the same live player/train/carriage identities, disabled flight and no seat mounting, at least 6 blocks of net carriage-local aisle progress in each direction (each leg measured separately, counting only ticks with measured carriage translation, so position jitter cancels out), continued power, a complete captured enclosure, and no hidden world floor. Floor contact is measured against the captured floor's actual collision top within 0.025 blocks over consecutive server observations. The raw vanilla server `onGround` flag is recorded but can be false on Create's dynamic collision surface, so it is not treated as proof or disproof of that contact.

Results and the silent `moving-passenger-train.mp4` are retained under `testing/build/reports/<run-name>/`; the assembled train and stations remain in `testing/build/run/<run-name>-server/world/`. Decode and visually inspect the footage before accepting it. The verified 1.1.0 Homestead run `moving-passenger-demo-11` passed `inside`, `rear`, and `returned`, with both JVMs exiting normally. During the 27.1-second recording the native carriage travelled 127.8 blocks, powered and lifted 0.25 blocks throughout, while the player made 9.66 blocks of net aisle progress toward the rear and 8.90 back; both aisle checkpoints and completion were measured at 0.4 blocks/tick (about 28.8 nominal km/h). Its frames were reviewed from exterior staging through boarding, the window views, the rear-window view, and the return to the doorway. This is a service-speed moving-carriage demonstration, not a maximum-speed or shader/hardware-GPU certification. The older archived `enclosed-passenger-demo-7` clip and downloadable save demonstrate parked walking only.

### Ponder and goggles screenshots

`testing/ponder_shots.py` starts a private loopback server and a real client, opens each Create Maglev Ponder scene, and saves screenshots at fixed scene ticks. It then places a substation client-side, puts on Engineer's Goggles, and captures the goggles readout. The runner refuses to reuse a run name and fails if Ponder opens a different scene than expected.

```sh
./build.sh :testing:prepareTestRuntime --console=plain
python3 testing/ponder_shots.py --accept-eula --run-name ponder-visual-review
```

Screenshots and `screenshots/index.json` are written to `testing/build/reports/<run-name>/`. Review the images themselves; the index only records what was captured.


## Changelog

### 1.1.0: Review fixes

- Fixed survival mining: the substation and all three powered bogeys took 15 seconds to break and dropped nothing with any pickaxe. Every addon block is now pickaxe-mineable and drops itself.
- Steel now uses the 1.20.1 convention tags `c:steel_ingots` and `c:steel_plates`, and every recipe consuming steel sheets accepts any `c:steel_plates` item.
- Substations are tracked persistently per dimension, so trains keep their traction in unloaded chunks; supply queries no longer touch (or keep loaded) the world. A substation placed under 1.0.x joins the registry, with its saved charge, the first time its chunk loads after upgrading.
- A pickup now draws from every covering substation, nearest first, instead of only the nearest one even when it is empty.
- Substations have an output rating (512 units/tick) and a larger default buffer (65,536). Weak supply now scales acceleration and caps speed at what the delivered energy sustains instead of stopping the train; status messages report weak supply.
- Curve speed now follows each curve's radius, √(25 × r) blocks/s with a 20 blocks/s floor, instead of a flat 20 blocks/s for every curve.
- Added a server config for tier speeds, acceleration, energy costs, curve and braking limits, and substation capacity, output, and radius.
- The maglev hums instead of playing wheel-on-rail loops and no longer shows wheel sparks or smoke; electric traction no longer chuffs steam or hisses on arrival.
- Added Engineer's Goggles readouts for substations, Ponder scenes, and a progression advancement tab.
- Rebalanced the guideway: one levitation module now upgrades eight reinforced tracks into eight pieces of guideway (previously two).
- Added native regressions for all of the above, including rated top speed per tier, and fixed the moving-walkthrough harness's per-leg aisle checks, its rear stop margin for client/server carriage lag, relative launcher paths, and Python bytecode ignores.
- Added a [player wiki](https://github.com/betizzel/create-maglev/wiki), maintained in `docs/wiki` and published with `tools/publish_wiki.py`.
- Test-harness demonstrations: the enclosed passenger-car builder and isolated Homestead recorder, extended to real scheduled travel with two-way on-foot aisle walking while moving, native collision-top support checks, actual NewAge energy use, and visible fixed trackside markers.

### 1.0.1: Rider and restart hardening

- Fixed a dedicated-server reload deadlock caused by querying neighboring redstone from the substation block-entity load callback while its chunk was still being promoted. Persisted breaker state and normal neighbor updates remain authoritative; live supply checks still enforce redstone isolation.
- Added a recorded native seated-passenger regression covering lift, real manual driving, power-loss braking, landing, and rider retention.
- Added an isolated two-JVM disk save/reload/resume regression with exact retained-charge and native railway-state checks.

### 1.0.0: Minecraft 1.20.1 Fabric

- Added electric, wheeled bullet, and dedicated-guideway maglev bogeys, track materials, and manufacturing progression.
- Added New Age/ESL substations with finite storage, loaded-range supply, redstone isolation, and transactional energy use.
- Added native train speed envelopes, terminal/reverse driving intent, controlled power-loss braking, synchronized whole-carriage lift, and persistence.
- Added separate packaged-JAR server regressions and actual-client recordings; no test-helper classes are included in the production addon.
