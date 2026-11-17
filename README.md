# Kapok

> Keeps the server thread from blocking on work it does not have to finish now: a chunk's load event no longer parks on its unloaded neighbours, chunk post-processing moves out of the wait to the head of the next tick, a chunk asked for from another thread no longer stops the tick, and a treasure map no longer generates the terrain it previews.

Minecraft 1.21.1 / NeoForge 21.1.x

---

## One file, two layers

Kapok is a single jar that works at two levels of the game. The outer layer is a loader
service: it runs before the mod list exists and changes how NeoForge reads your mods. The
inner layer is an ordinary mod, carried inside the same file, that changes how the game
hands out work once it is up. Drop the one jar in `mods/` and both are in place.

Only the inner layer shows in the Mods screen, as **Kapok**. The outer layer has no mod
id, because the work it does happens before mods exist. That is expected, not a broken
install.

The loader half also runs on a dedicated server, since a server starts the same loader. The
mod half runs on whichever side is generating terrain, so a dedicated server gets all of it
and singleplayer gets it from the integrated server.

## At startup (the loader service)

- **Mod classes are read once, not twice.** When NeoForge scans your mods it walks every
  `.class` in every jar twice: once for the ASM scan it genuinely needs, and once to hand
  the bytes to the signature verifier, which on an unsigned jar (nearly every mod) throws
  them away without recording anything. In a large modpack that second pass reads every
  class a second time and keeps nothing for an unsigned jar. Kapok skips it. Signed jars
  keep both passes, and the
  signature check that runs when a class is actually loaded takes a different path and is
  untouched.
- **From the second launch, not at all.** The ASM scan's result is a pure function of the
  jars you have installed, so Kapok writes it down the first time and reads it back on
  every launch after that. That is every class in every installed mod that is never
  parsed again until you add, remove, or update a mod. The stored
  scan is keyed on what is actually in each jar, read from the zip index without unpacking
  anything. If a stored scan cannot be read back, the loader scans that jar the way it
  always did and the log names the jar and the reason.

- **Jars inside jars live on disk, not in memory.** Many mods ship their libraries inside
  themselves, and a jar mounted from inside another jar is not a file the operating system
  knows about, so nothing can read a part of one — Java inflates the whole nested jar into
  memory and keeps it there for the rest of the session. In a large modpack that is many
  nested jars held in heap that exist only because the bytes had nowhere else to live.
  Kapok writes them out under `.cache/kapok-core/nested/` the first time it sees them
  and the loader opens them as ordinary files, reading a page at a time. On disk they come
  to roughly the same space they already take up inside the mods carrying
  them; a copy is rewritten when its mod changes, and copies belonging to mods you no
  longer have are removed at the end of startup. Which nested jars exist and which version
  of a duplicated library wins are still the loader's decisions and are untouched.

This half works on a dedicated server too, since a server runs the same loader.

## In the game (the mod)

- **A chunk's load event does not park on its unloaded neighbours.** While a chunk's
  `ChunkEvent.Load` is being posted on the server thread, a listener can ask the cache for a
  neighbouring chunk that is not loaded yet. Vanilla answers by parking the main thread in
  `managedBlock` until that chunk generates, and `managedBlock` keeps draining the task queue
  while it waits, so it runs the next chunk's post-processing, whose own neighbour reads park
  again. On ATM10 that chain held a single tick-and-a-half window for 36 to 50 seconds.
  Kapok opens a window around that one post: inside it, a lookup that does not require the
  chunk, is for a different chunk, and has nothing loaded yet answers `null`, which is the
  answer those callers already handle. A chunk that is there — at FULL, or with a proto chunk
  at the requested status — is returned as before, and so is every call that does require the
  chunk. The window is hidden while a wait runs unrelated queued tasks, so work that is not
  part of the listener keeps vanilla's behaviour, and it closes in a `finally`, so a throwing
  listener cannot leave it open. What changes is when the wait happens and which thread waits;
  nothing generates chunks elsewhere, and the world that comes out is the same.
  `-Dkapok.chunk.guard=false` turns it off, for comparing against vanilla.

- **A chunk asked for from another thread no longer stops the tick.** Vanilla answers a
  `getChunk` made off the main thread by running the whole call as a main thread task and
  joining it, so the main thread parks in `managedBlock` until the chunk generates while the
  caller sits in `join`; worldgen workers and structure locators asking a level for chunks
  produced 44-second stalls on ATM10. Kapok uses the future path the game already has
  (`getChunkFuture`): the main thread schedules the generation and returns to ticking, and the
  calling thread waits for the future. Two things the parked main thread used to provide for
  free are supplied here instead. A `requireChunk` wait holds a ticket at the same level until
  the future completes, because the game's own ticket expires after one tick and a ticking main
  thread would drop it mid-generation. And the "this chunk is already loading" answer is
  checked before scheduling, so a load listener waiting on the main thread and a worker asking
  for the chunk being loaded cannot wait on each other. The result handling is the game's:
  `null` when the chunk is absent and not required, an error when it is absent and required.
  `-Dkapok.chunk.offThreadFuture=false` turns it off.

- **Chunk post-processing runs at the head of the next tick, not inside a wait.** A chunk that
  has finished generating replays the block ticks for everything that was placed while it was
  still being built. For a fluid that is `FlowingFluid.tick`, which reads the six neighbouring
  blocks, and those reads ask the chunk cache with `requireChunk = true`, so a neighbour that
  is not at FULL yet parks the main thread. Kapok puts `postProcessGeneration` off to a later
  tick when it would run inside a main-thread chunk wait, or when the 3x3 neighbourhood it
  needs is not present at FULL, and runs the held chunks at the head of `ServerLevel.tick`,
  before that tick's scheduled block and fluid ticks, which keeps vanilla's order while moving
  the work out of the wait. A chunk whose neighbours never arrive is processed anyway after a
  bounded number of ticks, the pending queue is capped, and at most a fixed number are retried
  per level tick. The decision is a pure function of primitives with its own unit tests.
  `-Dkapok.chunk.deferPostProcess=false` turns it off.

- **Treasure-map previews do not generate unloaded terrain.** Drawing a treasure map asks
  the server for the biome of ground it has never loaded. In vanilla each of those lookups
  waits for that chunk to generate on the server thread — up to 1024 chunks for one map,
  from the 128x128 preview loop. Kapok marks the map-preview call tree and, while it
  runs, answers a biome lookup for a chunk the server has not loaded yet from the noise
  source instead of waiting; chunks that are already loaded are read as before, and
  everything that is not a map preview is untouched. The preview keeps its colours; only
  the wait is removed. This one keeps running with c2me installed, because c2me does not
  own the vanilla wait this avoids. `-Dkapok.chunk.noBiomeLoad=false` turns it off.

The chunk work runs on whichever side is generating terrain, so a dedicated server gets all
four and singleplayer gets them from the integrated server. It does not generate chunks on
another thread, shorten render or simulation distance, or change what world generation
produces; it changes when the waits happen and which thread waits. The weak-server lane Free
Server Saver covers is not this mod's job.

## With c2me installed

c2me already owns off-thread chunk loading, so Kapok's chunk mixins — the load-event window,
the off-thread future and the deferred post-processing — are not applied when c2me is present,
and chunk loading stays with c2me. The map-preview guard is not gated: c2me does not own the
vanilla wait a map preview causes, so that guard keeps running. When c2me is not installed the
chunk mixins apply as usual.

## No settings

There is no config screen and no config file. Everything above is on from the first launch,
because a player on a struggling machine should not have to know which numbers to type. The
`-Dkapok.` properties named above exist for comparing one run against vanilla, not for tuning.

## How to tell it is working

Near the top of `logs/latest.log`:

```
[KapokCore/]: Kapok Core active on NeoForge 21.1.231: mod classes will be read once at startup instead of twice
[KapokCore/]: Kapok bundle: loaded kapok-mod-0.1.0.jar from kapok-0.1.0.jar
[KapokCore/]: Kapok Core: jar-in-jar locator replaced (nested jars are written to .cache/kapok-core/nested and read from there)
[KapokCore/]: Kapok Core: nested jars on disk instead of in memory - 141 written, 116 reused, 0 mounted in place
```

The mod half is quiet in normal play. It logs one line at startup when c2me is present and its chunk changes are not applied, a warning when an off-thread chunk request has waited longer than 30 seconds, and at most two lines when the server stops.
If the loader service decided to switch itself off (another mod also registers a mod-file
reader, an unfamiliar NeoForge line, an error while reading a mod file), it says so and names
the reason; the mod half still loads.

Deleting the `.cache/kapok-core` folder in your game directory throws away the stored
scans and the nested jars written out beside them, and costs you one slower launch.

## Supported

| Minecraft | NeoForge | Forge | Fabric |
|---|:---:|:---:|:---:|
| 1.21.1 | ✅ | — | — |

## Building

`./gradlew build`. The file that ships is `core/build/libs/kapok-<version>.jar`; the
root project's `build/libs/kapok-mod-<version>.jar` is the inner layer and only ever
appears nested inside it. `./gradlew runBundle` launches a client with nothing from the
source sets, loading whatever sits in `run/mods/` — put the shipped jar there for a
production-shaped run.

### Checking the mixins

A mixin names its target in strings, so a `@Shadow` of a field that is not on the target
class, an `@Inject` into a method that does not exist, or an `@At` whose `target` is not
in the method body all compile without complaint and only fail when the game applies
them. `MixinTargetAuditTest` closes that: it reads the configs registered in
`neoforge.mods.toml`, reads the compiled mixins with ASM, and asks the classpath javac
compiled them against — the NeoForge artifact —
whether each name is there. Target classes, injector `method` selectors, `@At` targets
with their `ordinal` and their `require`/`allow` counts, `@Shadow`, `@Accessor`,
`@Invoker` and `@Local(argsOnly = true)` all get a line in
`build/reports/mixin-audit.txt`, including the ones it cannot check. It runs as part of
`./gradlew build` and takes about half a second.

Mixin ships an annotation processor that covers part of the same ground, and the reason
it is not turned on instead is worth writing down. It is already on the compile classpath
inside `sponge-mixin`, but Gradle gives javac a processor path of its own and this build
puts nothing on it, so it never runs. Turning it on would find a missing target class, a
missing `@Shadow` or `@Accessor` target and an injector `method` that resolves to
nothing — but every one of those is reported as `TARGET_ELEMENT_NOT_FOUND`, which the
processor maps to `Diagnostic.Kind.WARNING`. The build would stay green. And nothing
inside a method body is reachable to it at all: annotation processing sees declarations,
not instructions, so an `@At` target, an `ordinal` past the end, and a `require = 1,
allow = 1` that now matches twice are outside what it could ever check. Its own job is
generating a refmap, which 1.21.1 on NeoForge does not need because names are not
obfuscated at runtime and none of the configs asks for one.

For controlled client comparisons, `-Dkapok.diag.frames=true` records frame-start
intervals after a 20-second join warmup. The exit summary includes p50/p95/p99,
maximum interval, and retained/overflow counts. It includes VSync and focus throttling;
it measures frame intervals, not rendering CPU time. Ordinary launches register no
measurement listeners.

## License

[All Rights Reserved](LICENSE). Modpack inclusion is allowed without permission or credit.
