# Historical bag fixture

`travelbag-1.0.4-mc26.3.dat` is an unchanged compressed NBT bag file produced
by TravelBag 1.0.4 on Minecraft 26.3 during the 2026-10-25 compatibility tests.
It contains ten diamonds using the version-1 storage schema. The regression
test imports it into isolated temporary storage and verifies loading, resaving
and reopening. It also checks a derived copy with DataVersion omitted to
exercise the older unversioned layout. No real player data is included.
