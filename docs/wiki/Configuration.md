# Configuration

Balance values live in a server config file, `config/create_maglev-server.toml`, inside the game or server folder. The server sends its values to players when they join, so on a multiplayer server only the server's file matters. Speeds are in blocks per second, with one block per metre.

## Tiers

Each tier, `tiers.electric`, `tiers.bullet`, and `tiers.maglev`, has the same four settings:

| Setting | Meaning | Electric | Bullet | Maglev |
| --- | --- | --- | --- | --- |
| `topSpeed` | Top speed, in blocks/s | 40 | 60 | 80 |
| `acceleration` | Acceleration, in blocks/s² | 5 | 6 | 8 |
| `idleEnergyPerBogey` | Energy each bogey draws per tick, even when stopped | 8 | 16 | 32 |
| `speedEnergyCoefficient` | Extra energy per bogey per tick: `ceil(speed² × coefficient)`, with speed in blocks per tick | 4 | 6 | 8 |

| Setting | Default | Meaning |
| --- | --- | --- |
| `tiers.bulletSpeedOnOrdinaryTrack` | 40 | Bullet speed cap on track other than Reinforced Standard Track, in blocks/s |

## Track

| Setting | Default | Meaning |
| --- | --- | --- |
| `track.curveLateralAcceleration` | 25 | Sideways acceleration allowed on curves, in blocks/s². A curve of radius *r* allows √(this × *r*) blocks/s |
| `track.minimumCurveSpeed` | 20 | Speed always allowed on a curve, however tight, in blocks/s |
| `track.emergencyBraking` | 16 | Braking when power is lost, and ahead of unpowered track, curves, and track ends, in blocks/s² |

## Substations

| Setting | Default | Meaning |
| --- | --- | --- |
| `substations.capacity` | 65,536 | Energy each substation can store |
| `substations.maxOutputPerTick` | 512 | Most energy one substation delivers per tick, shared by every train it powers |
| `substations.supplyRadius` | 64 | Range in blocks, measured in all three dimensions, within which a substation powers bogeys |
