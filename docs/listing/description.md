![A maglev passenger car waiting at its station](https://raw.githubusercontent.com/wiki/betizzel/create-maglev/images/maglev-station.png)

**Create: Maglev** adds electric railways to Create. Build trackside **Traction Substations**, charge them with Create: New Age electricity, and upgrade your trains through three powered tiers, ending with a maglev that floats above its own guideway.

## Features

- **Three powered bogeys:** Electric bogeys reach 40 blocks/s on ordinary track, Bullet bogeys reach 60 blocks/s on Reinforced Standard Track, and Maglev bogeys reach 80 blocks/s on the dedicated Maglev Guideway.
- **Traction Substations** store New Age electricity and power every bogey within 64 blocks. Overlapping substations share the load, and weak supply slows a train down instead of stopping it.
- **Real levitation:** a powered all-maglev train lifts its whole carriage a quarter of a block above the guideway, then brakes and settles back down if it loses power.
- **Speeds that respect the track:** each curve has a speed limit based on its radius, and trains brake ahead of curves, unpowered track, and the end of the line.
- **Native Create trains:** stations, schedules, Train Controls, and carriage building all work as usual. Trains built only from Create's own bogeys are unchanged.
- **Keeps running while you're away:** substations keep powering trains in unloaded chunks, just as Create keeps running scheduled trains there.
- **In-game help:** Ponder scenes, Engineer's Goggles readouts, and an advancement tab that follows the progression.
- **Server config** for speeds, energy costs, curve limits, and substation capacity.

![A Ponder scene showing a carriage raised above the Maglev Guideway](https://raw.githubusercontent.com/wiki/betizzel/create-maglev/images/ponder-levitation.png)

## Getting started

1. Make Steel Ingots by heated mixing, and press them into Steel Sheets.
2. Craft a Traction Substation, attach a New Age Electrical Connector, and wire it to a generator with Copper Wire.
3. Build Electric Traction Bogeys, place them on the track at a Train Station, and assemble your train.

The wiki walks through every step and covers the full recipe chain, energy costs, and troubleshooting.

## Requirements

Minecraft 1.20.1 with Fabric Loader, Fabric API, Create Fabric 6.0.8.1, Create: New Age 1.2.0, and Forge Config API Port. Install Create: Maglev on the server and on every client.

## Links

- [Wiki](https://github.com/betizzel/create-maglev/wiki)
- [Source code](https://github.com/betizzel/create-maglev) (MIT License)
- [Report a problem](https://github.com/betizzel/create-maglev/issues)
