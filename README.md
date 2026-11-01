# Create Maglev

A Minecraft 1.20.1 Fabric addon for Create and Create: New Age: build an electrical industry, upgrade ordinary trains into wheeled high-speed trains, then build a dedicated magnetic-levitation railway.

This extends Create's actual train system. You still build the carriage body, assemble it at a station, and use Create's controls, schedules, and railway graph. The addon supplies propulsion bogeys, guideways, manufacturing parts, and electrical traction. It is not a separate vehicle system or a prebuilt train body.

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
3. Put `build/libs/create-maglev-1.0.1+mc1.20.1.jar` into the cloned instance's `mods` directory, replacing the previous Create Maglev JAR rather than installing both versions. Multiplayer requires the addon and its dependencies on both the client and server.
4. Start a disposable creative world first. The **Create Maglev** creative tab contains the bogeys, tracks, substation, and parts.

`build/production-pack/mods/` contains the five-mod runtime set, including the addon, with exact sizes and SHA-256 hashes in `build/production-pack/manifest.json`. Nested upstream libraries remain inside their upstream JARs. Do not install the test-helper JAR from `testing/build/libs`, development JARs, or sources JARs into your normal instance.

## Progression

These are rated straight-line automatic speeds at 20 ticks/second, assuming one block is one metre. Create's native manual throttle modifier, curves, mixed consists, power coverage, and braking can reduce actual speed.

| Stage | Running gear and railway | Rated speed | Idle energy per driven bogey/tick |
| --- | --- | --- | --- |
| Workshop | Existing Create bogeys and track | Existing Create configuration, unchanged | Native Create behaviour |
| Electric | Electric bogeys on standard or reinforced track | 40 blocks/s, about 144 km/h | 8 ESL units |
| Bullet | Bullet bogeys; reinforced track unlocks the higher cap | 60 blocks/s, about 216 km/h; ordinary track caps at 40 blocks/s | 16 ESL units |
| Maglev | Maglev bogeys on the dedicated maglev guideway | 80 blocks/s, about 288 km/h | 32 ESL units |

Addon-driven trains are limited to 20 blocks/s on curves. A mixed wheeled consist uses its lowest bogey tier; stock bogeys therefore limit an upgraded consist. Do not mix wheeled and maglev bogeys: their track requirements are incompatible. Tier upgrades require crafting, not free wrench cycling.

### Build the factory

- Make the addon's steel ingots by heated mixing, and press them into steel sheets. Laminated cores, wound coils, traction motors, and control units provide the intermediate manufacturing chain.
- **Electric bogey:** sequenced assembly starts with a Create railway casing and deploys a steel sheet, traction motor, and control unit.
- **Bullet bogey:** upgrade an electric bogey with a steel sheet, a New Age overcharged iron sheet, and a control unit.
- **Levitation module:** craft an unenergized module from its constituent parts, then process that single item in a real New Age energiser using **4096 energy**. The material cost is paid before energising; electricity alone cannot create the module.
- **Maglev bogey:** upgrade a bullet bogey using a levitation module, an overcharged iron sheet, and a wound coil. Build the dedicated guideway rather than trying to float on ordinary rails.

The exact recipes are in [`src/main/resources/data/create_maglev/recipes`](src/main/resources/data/create_maglev/recipes). The carriage's appearance is yours to build with Create blocks: a wheeled bullet train and a maglev can have very different bodies without changing the railway controls.

## Electrical traction

Use New Age's actual generator, magnets, carbon brushes, electrical connectors, and copper wire to charge a **Traction Substation**. Attach a New Age connector to the substation and wire it into the generating network; a shaft alone does not charge the substation.

- Each substation stores **8192 ESL energy units** and supplies a **64-block spherical radius**, including height. Put it comfortably inside the route's coverage rather than right on a diagonal range boundary.
- Every driven bogey's pickups must be covered by loaded, enabled substations. Coverage can be handed between substations along a line. The addon does not force-load their chunks.
- Right-click a substation with an empty hand to inspect charge and range. A comparator reads its charge level.
- Redstone input opens the traction breaker and disables supply. It does not erase the buffer. Merely cutting a charging wire leaves stored energy available until it is used.
- Powered bogeys consume electricity even at rest. Their extra running cost rises with speed squared: `idle + ceil(speed² × coefficient)`, where speed is blocks/tick and the coefficient is 4, 6, or 8 for electric, bullet, or maglev respectively. A two-bogey consist at its full tier cap consequently needs 48, 140, or 320 units/tick; size generation and buffering accordingly.
- A train's requested energy is committed atomically across its selected substations. Insufficient charge cannot partially debit that train's other suppliers. There is no hidden coal fallback for upgraded consists; all-stock Create trains remain native.

The route preview looks ahead for curves, unsupported track, missing supply, and track ends. A loss of traction produces controlled braking; a maglev carriage lowers onto its guideway rather than disappearing or retaining a powered hover after reload. The whole carriage entity is lifted, not just a decorative bogey model.

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

- The unfiltered server batch contains **61 native Create tests and 8 addon tests**. Addon cases exercise native station assembly and destination arrival, real New Age generation and copper-wire delivery, finite shared-supply exhaustion, native energising and its unpowered counterfactual, live curve traversal, incompatible-track rejection, carriage lift/persistence, and recovery of a broken purchased electric bogey.
- Native Create's code and assertions are untouched. The isolated helper provides two fixture-data corrections inherited from the ports workflow: a Forge/convention-tag mismatch and Forge-versus-Fabric fluid units. The helper also seeds Create's recipe randomness for the batch. Those overrides do not ship in the addon.
- Seven client scenes cover stock trains, real keyboard-driven electric trains, reinforced-track bullet trains, powered maglev movement, an unpowered maglev keyboard-drive attempt, a real traction-breaker outage with braking and landing, and a seated maglev passenger through the full rise/drive/brake/land cycle. Server fixture assembly and automatic schedules are explicitly labelled; manual scenes use actual OS-level W input. Automatic scenes use a labelled static side camera on a real off-track perch.
- Positive charge comes from native generators and wire, not direct buffer writes. Motion comes from Create controls or schedules, not injected train velocity. Levitation assertions compare the real carriage position with its native track anchor, rather than trusting a reported lift value alone.
- The passenger scene boards a real assembled Create seat through native interaction. It checks the live player and carriage by UUID, the passenger-to-vehicle relationship, Create's seat mapping and control ownership, actual body/player displacement, and stable retention after landing. It does not teleport the rider along the route.
- The restart runner saves a moving, raised maglev with its generator stopped, wire disconnected, and real redstone breaker open, then reopens that same world in a second dedicated-server JVM. Before native ticking it checks the persisted train/graph UUIDs, schedule, style, track pose, speed, lift, breaker, and exact stored energy. The owned world's spawn is set near the fixture so vanilla preloads entity-ticking chunks before the first post-reload train tick. No train or power fixture is rebuilt, no ticks are frozen, and no body state is injected. The live body must still be moving and raised when first resolved, then provide intermediate braking and physical descent samples before landing; a carriage that appears only after settling cannot pass. Finally, closing the real breaker must resume the saved schedule using retained charge.

### Verified checkout results

| Verification | Observed result |
| --- | --- |
| Required native server GameTests | 69 passed, 0 failed |
| Actual-client scenarios | 7 passed, 0 failed |
| Unpowered maglev, real W attempt | No movement and no physical lift |
| Traction-breaker outage | 14 intermediate braking samples, then zero speed and zero physical lift; stored charge retained |
| Seated maglev passenger | Retained native seat/control ownership through 0.25-block lift, keyboard driving, braking, and stable landing |
| Fresh-JVM disk restart | 20 live-body samples, including 8 braking and 18 descent steps; zero final speed/physical lift |
| Restart energy persistence | Exactly 8124 units retained while isolated; closing the breaker resumed the saved schedule and consumed 526 units |
| Distributable audit | Java 17 bytecode, valid JSON/PNG resources, no test-helper entries |

The seven final MP4s were visually reviewed, including the powered carriage/guideway clearance, the settled carriage after the breaker opened, and the real seated-player view. Packaged input and video hashes were checked against the recording report. The additional archive inspection is recorded in `testing/build/reports/production-artifact-audit.json`. The previously accepted 1.0.0 JAR, recordings, reports, and hashes remain preserved under `testing/build/reports/baseline-1.0.0/`; failed-before restart evidence is also retained separately from the final passing report.


Outputs:

- `testing/build/reports/server-gametests.xml`
- `testing/build/test-runtime.json`, including exact packaged input hashes and fixture overrides
- `testing/build/reports/recorded/report.json`
- `testing/build/reports/recorded/*.mp4`, `server.log`, `client.log`, and client events
- `testing/build/reports/restart/report.json`, phase results, checkpoint, input hashes, and separate server logs

The recording report checks encoding and native gameplay outcomes; encoded-video checks alone are not visual review. Default Minecraft/Create rendering is the target exercised here. Additional shader packs, other train addons, and the complete NUKES Homestead pack are not certified by this isolated runtime.

## Changelog

### 1.0.1: Rider and restart hardening

- Fixed a dedicated-server reload deadlock caused by querying neighboring redstone from the substation block-entity load callback while its chunk was still being promoted. Persisted breaker state and normal neighbor updates remain authoritative; live supply checks still enforce redstone isolation.
- Added a recorded native seated-passenger regression covering lift, real manual driving, power-loss braking, landing, and rider retention.
- Added an isolated two-JVM disk save/reload/resume regression with exact retained-charge and native railway-state checks.

### 1.0.0: Minecraft 1.20.1 Fabric

- Added electric, wheeled bullet, and dedicated-guideway maglev bogeys, track materials, and manufacturing progression.
- Added New Age/ESL substations with finite storage, loaded-range supply, redstone isolation, and transactional energy use.
- Added native train speed envelopes, terminal/reverse driving intent, controlled power-loss braking, synchronized whole-carriage lift, and persistence.
- Added separate packaged-JAR server regressions and actual-client recordings; no test-helper classes are included in the production addon.
