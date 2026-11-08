# Troubleshooting

While you hold a train's Train Controls, the action bar shows a message whenever the train is short of full power.

## "No traction power. Connect or charge an enabled substation within 64 blocks."

The train has no usable supply. Check these in order:

1. **Range:** both wheel positions of every Create: Maglev bogey must be within 64 blocks of a substation. On a long train, the far bogeys may be out of range.
2. **Charge:** right-click each nearby substation with an empty hand. A charge of 0 means it isn't being charged; see [A substation won't charge](#a-substation-wont-charge).
3. **Breaker:** a status of "Breaker open, supply isolated" means a redstone signal reaches the substation. Remove the signal.
4. **Shared output:** a substation delivers at most 512 per tick across all the trains it powers. If several trains share one, that may not even cover their idle draw. Add a substation whose range overlaps.

## "Weak traction supply: acceleration and top speed are reduced."

The substations in range deliver less than the train asks for: they are running low, or other trains are sharing their output. Recharge them, or add substations whose ranges overlap. The train keeps running, just more slowly.

## "This bogey is incompatible with the current track material."

- A Maglev Bogey is on something other than Maglev Guideway, or an electric or bullet bogey is on Maglev Guideway.
- The train mixes maglev and wheeled bogeys. Use one kind or the other.

## A substation won't charge

- It needs electricity, not rotation. Attach a Create: New Age Electrical Connector and wire it with Copper Wire to a running generator.
- A redstone signal opens the breaker, which also blocks charging.
- It may already be full at 65,536.

## A bullet train never goes above 40 blocks/s

Bullet bogeys reach 60 blocks/s only on Reinforced Standard Track. Also check the train for Create or electric bogeys: the slowest bogey sets the pace.

## The train slows down on curves

That is the curve limit: a curve of radius *r* allows √(25 × *r*) blocks/s. Widen the curve; see [Curves](Trains-and-Tiers.md#curves).

## A maglev doesn't float

- It floats only when every bogey on the train is a Maglev Bogey and the train has power.
- It rises gradually, over about a second.

## Substations drain while trains are parked

Powered bogeys draw their idle energy whenever they are in range of a working substation, even when stopped. See [Planning a line](Traction-Power.md#planning-a-line) for a redstone-switched depot.

## Trains keep running while I'm far away

That's expected. Create keeps running scheduled trains in unloaded chunks, and their substations keep powering them there.
