# Adding servers

The point of OpenDonut's design is that adding a server is a configuration change, not a code change. This
document walks through going from two servers to three, then to twenty, and the two decisions that actually
matter: **where the new boundary goes**, and **how the existing world is affected**.

Nothing here requires editing Java, rebuilding the mod, or touching another server's files.

---

## The short version

1. Decide the rectangle the new server owns.
2. Add it to `servers` in `opendonut.yml`, cut out of an existing region or out of unassigned space.
3. Run `check` and `report`.
4. Copy the file (and the secret) to the new backend, and the updated file to the proxy and the existing
   backends.
5. Start the new backend, then `/opendonut reload` on the proxy.
6. Point the new backend's existing region at the world's seed and let it generate.

Step 4 is the one people skip. The proxy must have the updated table *before* it can route to the new server,
and each existing backend must have it too, because each backend decides whether a player has left *its own*
region using its own copy.

---

## Two servers to three: adding `north`

Current layout — two halves of a 200000 × 200000 border:

```
                 Z = -100000
        ┌──────────────┬──────────────┐
        │              │              │
        │     west     │     east     │
        │              │              │
        └──────────────┴──────────────┘
                 Z =  100000
       X = -100000         X = 0         X = 100000
```

Suppose a third machine should take the top half of `west`'s territory. `west` gives up
`X = [-100000, 0)`, `Z = [-100000, 0)`:

```yaml
servers:
  - id: west
    address: "127.0.0.1:25566"
    bounds: { min-x: -100000, max-x: 0, min-z: 0,      max-z: 100000 }   # shrunk: min-z 0 now

  - id: east
    address: "127.0.0.1:25567"
    bounds: { min-x: 0,       max-x: 100000, min-z: -100000, max-z: 100000 }

  - id: north
    address: "127.0.0.1:25568"
    bounds: { min-x: -100000, max-x: 0,      min-z: -100000, max-z: 0 }  # the piece west gave up
```

Verify before starting anything:

```bash
java -jar opendonut-tools-0.1.0-dev.jar report opendonut.yml
```

```
0 errors, 0 warnings, 6 informational messages
...
Coverage
  coverage: 100.000% of 40000000000 blocks covered (0 unassigned)
Servers
  [0] west  127.0.0.1:25566  X=[-100000,0) Z=[0,100000)          (6250 x 6250 chunks)
  [1] east  127.0.0.1:25567  X=[0,100000) Z=[-100000,100000)     (6250 x 12500 chunks)
  [2] north 127.0.0.1:25568  X=[-100000,0) Z=[-100000,0)         (6250 x 6250 chunks)
```

And check the new seams by hand:

```bash
java -jar opendonut-tools-0.1.0-dev.jar route opendonut.yml -1 -1    # north
java -jar opendonut-tools-0.1.0-dev.jar route opendonut.yml -1  0    # west
java -jar opendonut-tools-0.1.0-dev.jar route opendonut.yml  0 -1    # east
```

Three regions meeting at a corner is the case where a one-block mistake in a hand-written bound is invisible in a
paragraph of coordinates and obvious in these three answers.

### Watch out for

* **Coverage.** If the piece you cut out was not exactly the piece you added, `report` says
  `WORLD_UNCOVERED` with the missing rectangle. That is exactly the check you want after hand-editing bounds.
* **Overlaps.** If the old region was not shrunk, `REGION_OVERLAP` names the two ids and the shared area. Two
  servers writing the same chunk is the one outcome OpenDonut exists to prevent, so this is fatal.
* **Chunk alignment.** `min-z: 0` is fine (0 is a multiple of 16). `min-z: -1` is not, and would give two
  servers a shared chunk.

## Using unassigned space instead

If `validation.require-full-coverage` is `false` — the default — the network may have unowned areas, which is how
a network is usually grown: start with the territory you need, leave the rest of the border empty, and fill it in
later.

```yaml
validation:
  require-full-coverage: false
```

```
[WARN] WORLD_UNCOVERED (servers:7) 20000000000 of 40000000000 blocks (50.000%) inside the world border are
       owned by no server
    unassigned areas (largest first):
      X=[0,100000) Z=[-100000,100000) (20000000000 blocks)
```

Players who walk into an unowned coordinate are handled by `transfer.unassigned-area-policy`: with the default
`allow` they simply stay there, and the backend logs the situation once. With `return` they are pushed back
inside the server they are on. Neither is wrong; `allow` is the gentler default while a network is being built,
and `return` is the choice for a finished network that must never have a reachable gap.

## Scaling to twenty servers

Nothing changes structurally. A 200000 × 200000 border divided into a 5 × 4 grid gives twenty regions of
40000 × 50000 blocks:

```yaml
servers:
  - id: r0c0
    address: "10.0.0.11:25566"
    bounds: { min-x: -100000, max-x: -60000, min-z: -100000, max-z: -50000 }
  - id: r0c1
    address: "10.0.0.12:25566"
    bounds: { min-x: -60000,  max-x: -20000, min-z: -100000, max-z: -50000 }
  # ... eighteen more
```

The lookup engine is built for this: the index compresses the X axis into at most `2n - 1` slabs — twenty regions
typically produce far fewer, because neighbours share edges — and a lookup is two binary searches. A lookup costs
the same whether the network has two regions or two hundred, and it runs once per player per tick.

Two things to get right at scale:

* **Check the ports.** Every configured address should be unique, and each backend identifies itself by its port.
  Ports that repeat across different hosts are legal but make auto-detection ambiguous; the validator warns, and
  those machines need an explicit `backend.server-id`.
* **Give the regions names that mean something.** The id appears in logs, in `/opendonut route` output and in the
  proxy's server list. `r3c2` is fine for a generated file and annoying in an incident.

## What happens to the world when you add a server

**Existing terrain does not move.** When `west` shrinks and `north` takes over part of its rectangle, the new
server starts with an empty world folder. With the same seed and the same world-generation settings, the terrain
it generates for its region is *identical* to what `west` had generated — the same blocks in the same places —
but it is a separate copy in a separate folder.

That has three practical consequences, and they are the honest ones:

1. **Unexplored land is seamless.** If nobody had been there, the boundary is invisible.
2. **Player-built structures in the handed-over area exist only in the old world folder.** They will not appear on
   the new server unless you copy the region files yourself (with the old server stopped). OpenDonut does not do
   this, because doing it wrongly while a server is writing is how worlds get corrupted.
3. **The old server's copy of those chunks is now orphaned.** It is never loaded again, because the old server no
   longer owns those coordinates.

This is why the recommended order is: choose new territory that is *unexplored* (outward growth), or accept an
explicit one-off region-file copy when you must cut through inhabited land. The test suite's
[coverage checks](../tests/README.md) exist to make sure the *coordinates* are right; the *data* question is a
management decision, and pretending otherwise would be worse than saying it plainly.

## Removing a server

1. Give its rectangle to a neighbour in `opendonut.yml` — either one neighbour taking the whole thing, or several
   taking slices. Coverage must stay complete if `require-full-coverage` is `true`.
2. `check` and `report`.
3. Stop the server being removed.
4. Copy the updated file everywhere, then `/opendonut reload` on the proxy.

Players who were on the removed server are disconnected when it stops; the next time they connect, Velocity places
them on a server according to its normal rules and the backend's join check moves them to the region that owns
their saved coordinates. Nothing special has to be done for them.

## After every change

Run these three, every time, in this order:

```bash
java -jar opendonut-tools-0.1.0-dev.jar check  opendonut.yml    # exit code 0 means safe to deploy
java -jar opendonut-tools-0.1.0-dev.jar report opendonut.yml    # eyeball the routing table and coverage
# then, in game:
/opendonut route <x> <z>        # on the proxy, and on each backend, for a point on each side of the new seam
```

`check` is designed to be run from a deployment script: it exits `0` when the configuration is usable, `1` when it
has errors, and never starts a server in a half-configured state.
