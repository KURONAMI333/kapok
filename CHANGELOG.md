# Changelog

## 0.1.0

First release. Kapok keeps a large modpack from stopping: the server thread no longer blocks
on chunk work it does not have to finish now, mod classes are read once at startup, and
libraries bundled inside mods stay on disk instead of in heap. One JAR, no configuration: each
part checks its environment and steps aside when it does not apply.

Requires Minecraft 1.21.1 and NeoForge 21.1.231 or newer, on Java 21. The client and a
dedicated server are both supported.

### Startup, before the mod list exists

- Mod classes are read once at startup instead of twice: the second pass hands an unsigned
  JAR's bytes to the signature verifier, which discards them. Signed JARs keep both passes.
- The loader's annotation scan is stored on disk and read back on later launches, so unchanged
  mods are not parsed again. A JAR that changed is scanned the way it always was, and the log
  names a stored scan that could not be read.
- A scan read back from disk keeps one copy of each name that many mods repeat, instead of
  one copy per mod, and the loader holds those scans for the whole session. The first launch
  of a pack scans for real and is not affected.
- A stored scan that no launch has read for 30 days is removed at startup, so scans of mods
  you removed or updated do not pile up. Scans a launch reads are kept, however large the pack. A launch that was killed while
  writing leaves a temporary file behind; one older than an hour is removed at startup, and a
  copy another launch is writing right now is left alone.
- Libraries bundled inside mods are written under `.cache/kapok-core/nested/` and opened as
  ordinary files, instead of being inflated into heap for the rest of the session. The loader
  still chooses which nested libraries exist and which version of a duplicated library wins.
  A copy already on disk is recognised by its length rather than by reading it back, so a
  launch no longer reads every bundled library a second time.

### In the game

- While a chunk fires its load event, a lookup for a neighbour that is not loaded answers
  "absent" instead of waiting for that neighbour to generate. The checks around that answer
  cost less on every chunk lookup, whether or not the window is open.
- A chunk asked for from another thread no longer stops the tick: the main thread schedules
  the generation and keeps ticking instead of blocking until the chunk is ready.
- Chunk generation waits less on its own neighbours: a finished chunk's post-processing
  moves to the head of a later tick when it would run inside a chunk wait or its 3x3
  neighbourhood is not ready, with a bounded queue and a bounded retry. The chunk is sent to
  players after its post-processing has run. World generation output is unchanged. A chunk
  with nothing to post-process is not moved: no post-processing step, no upgrade to replay
  and no waiting block entities means the step is over, not deferred. The replay is also
  limited by time, not only by count: after a stall, the chunks that piled up are spread
  over the following ticks instead of all running at once.
- If putting a chunk back on the send list throws, the exception is logged once and the rest
  of the tick's chunk work carries on. The chunk keeps its blocks and is sent the next time
  it is prepared; before, the exception reached the crash handler.
- A treasure map now answers biome lookups for unloaded ground from the noise source
  while the map is being drawn, instead of waiting for that chunk to generate (up to 1024
  chunks for one map). Chunks that are already there are read as before, and everything
  that is not a map preview is untouched.
- A chunk that was loaded again while waiting to unload no longer keeps the server thread
  retrying its unload. Vanilla requeues that retry at once for as long as the chunk is
  being generated around, which used up the rest of the tick whenever it happened, and
  during shutdown could keep the server thread in that loop so the world was never saved. The skipped retries had nothing left to do: the chunk
  is unloaded the next time it is dropped, as before.
- With c2me installed the chunk changes above are not applied, because c2me already loads
  chunks off the main thread. The map change still applies.
