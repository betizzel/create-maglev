# Getting started: your first electric train

This builds the cheapest powered train: Electric Traction Bogeys on ordinary Create track, fed by one substation. You need Create and Create: New Age, plus the usual Create machines: a press, a mixer, and deployers on a belt for sequenced assembly.

## 1. Make steel

1. Mix one Iron Ingot with one Coal in a Basin under a Mechanical Mixer, with a lit Blaze Burner beneath the Basin. This heated mixing makes a Steel Ingot.
2. Press the Steel Ingot with a Mechanical Press to make a Steel Sheet.

Steel sheets from other mods work too, as long as they carry the `c:steel_plates` tag.

## 2. Craft a Traction Substation

It takes three Steel Sheets and five Create: New Age parts:

| | | |
| --- | --- | --- |
| Carbon Brushes | Steel Sheet | Carbon Brushes |
| Copper Wire | Generator Coil | Copper Wire |
| Steel Sheet | Electrical Connector | Steel Sheet |

## 3. Charge it

1. Build a working Create: New Age generator. New Age's own Ponder scenes show how.
2. Attach an Electrical Connector to the substation and connect it to the generator's network with Copper Wire. Turning a shaft next to the substation does not charge it; it needs electricity.
3. Check the charge: right-click the substation with an empty hand, or look at it wearing Engineer's Goggles.

Keep redstone signals away from the substation. Redstone opens its breaker, which stops both charging and supply.

## 4. Place it beside the line

A substation powers every bogey wheel within 64 blocks of it, measured in a straight line, height included. One substation right beside a straight track covers about 128 blocks of it. For a longer line, add more substations with overlapping ranges; see [Planning a line](Traction-Power.md#planning-a-line).

Substations keep working when their chunks unload, so nothing needs chunk loading.

## 5. Make Electric Traction Bogeys

Each bogey takes a short chain of parts. Every recipe is on the [Recipes](Recipes.md) page.

1. **Wound Copper Coil:** sequenced assembly on Copper Wire.
2. **Laminated Magnetic Core:** crafted from Magnetite Blocks and Steel Sheets.
3. **Traction Motor:** sequenced assembly on a Steel Sheet, using the coil and the core.
4. **Traction Control Unit:** crafted from Redstone, Electron Tubes, Overcharged Iron, and an Andesite Casing.
5. **Electric Traction Bogey:** sequenced assembly on a Train Casing, deploying a Steel Sheet, the Traction Motor, and the Traction Control Unit.

## 6. Build and run the train

Build it like any Create train. Next to a Train Station, place the Electric Traction Bogeys on top of the track, where Create's own bogeys would sit; they line up with the rails. Build the carriage on them, add Train Controls, and assemble the train at the station.

Drive it, or give it a schedule. If it won't move, hold the Train Controls and read the action bar, then see [Troubleshooting](Troubleshooting.md).

Use Create: Maglev bogeys throughout. A train runs at the pace of its slowest bogey, so a single Create bogey holds the whole train to Create's ordinary train speed.

## Next steps

- **Bullet train:** upgrade Electric Traction Bogeys into Bullet Traction Bogeys and lay Reinforced Standard Track for 60 blocks per second.
- **Maglev:** upgrade to Maglev Bogeys and build a Maglev Guideway for 80 blocks per second, floating above the track.

See [Trains and Tiers](Trains-and-Tiers.md).
