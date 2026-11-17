Removes several waits that only show up in a large modpack: mod classes are read once at startup, and the server thread no longer waits on chunk work it can finish later.

There is nothing to configure. One JAR, no config file, no config screen, and every part is on from the first launch.

## Features

- **Mod classes are read once at startup.** On an unsigned JAR the loader reads the whole file a second time to check for signatures, and on that JAR the check can only answer that it carries none. Kapok skips the second read and returns that answer directly. Signed JARs are left alone, and the check that runs when a class is loaded still runs.
- **The startup scan is cached.** The classes and resources inside each mod are recorded under `.cache/kapok-core/`, keyed on the JAR's contents. A jar that changed gets a new key and is scanned again, so a stale result is never reused.
- **Nested libraries stay on disk.** Libraries shipped inside a mod JAR are unpacked once to `.cache/kapok-core/nested/` and read from there as ordinary files, instead of being inflated into a copy that is held for the whole session.
- **Chunk loads no longer wait for unloaded neighbours.** While a chunk fires its load event, a neighbour lookup for a chunk that is not loaded returns "absent" instead of waiting for it to generate. The chunk is generated later, as part of the same work, not skipped.
- **Off-thread chunk requests keep the tick running.** When another thread asks for a chunk, the main thread only schedules the generation and goes back to ticking instead of blocking until that chunk is ready.
- **Chunk post-processing moves to the next tick.** When a chunk's neighbours are not ready yet, its post-processing is retried at the start of a later tick rather than at the end of the current one. At most 4096 chunks wait and at most 16 are retried per tick; a chunk that has waited 40 ticks is retried whether or not its neighbours are there. Players receive such a chunk after its post-processing has run, as in vanilla.
- **Map previews skip terrain generation.** Rendering a treasure map asks for biomes across a 128x128 area, which used to generate up to 1024 chunks for a single map. Those lookups are now answered from the noise source, the same place a generated chunk takes its biome from.

## Measured

All numbers come from a dedicated server (Minecraft 1.21.1, NeoForge, Java 21) on Windows 11 with a Core i7-13700F and 32 GB of RAM, using the same world seed with and without Kapok. Each figure is the median of 5 runs per side. In the charts, bars are medians and dots are individual runs.

### Memory after startup

![Server memory in use after startup, with and without Kapok](https://media.forgecdn.net/attachments/description/1812906/description_904fae0b-513a-4360-ad28-e0d6f3d682dc.png)

| Modpack | Heap in use after startup, without → with | Saved |
|---|---|---|
| All the Mods 10 (8.6) | 2,384 MB → 2,180 MB | 204 MB (8.6%) |
| All of Create (2.7) | 989 MB → 850 MB | 139 MB (14%) |
| All the Mons (2.0.1) | 2,355 MB → 2,121 MB | 234 MB (9.9%) |

"Heap in use" is the memory the server still holds after a full garbage collection, taken once the server reports Done. In All the Mods 10 and All the Mons, most of the difference is byte arrays, which matches nested libraries no longer being held in memory. The first launch, before the cache exists, saves about the same.

The median time to Done differed by 2% or less in all three packs, which is smaller than the run-to-run spread. Kapok does not make startup faster in these packs.

### Long freezes when memory runs short

![Ticks that froze for over 1 second with a 6 GB heap, with and without Kapok](https://media.forgecdn.net/attachments/description/1812906/description_f58001f6-7254-40f8-9cbf-eee0649fac39.png)

**In this test the server was limited to 6 GB of memory (`-Xmx6G`).** Eight simulated players flew into new terrain at about 30 blocks per second for 5 minutes. This load filled the 6 GB heap in all three packs.

| Modpack | Ticks that took over 1 second, without → with | Time spent in full garbage collection | 95th percentile of per-second average tick time |
|---|---|---|---|
| All the Mods 10 | 18 → 12 | 36 s → 24 s | 193 ms → 69 ms |
| All of Create | 11 → 7 | 19 s → 14 s | 82 ms → 81 ms |
| All the Mons | 48 → 42 | 143 s → 127 s | 442 ms → 416 ms |

- All the Mods 10: the ranges of all three numbers did not overlap between the 5 runs with and the 5 runs without Kapok.
- All of Create: only the count of ticks over 1 second separated (10 to 12 without, 5 to 9 with). The other two columns overlap.
- All the Mons spent about 2 to 3 minutes of the 5 in garbage collection. Its results varied more from run to run than the difference, so we do not count it as an improvement.
- The total time the server ran below 19 TPS was the same with and without Kapok in all three packs. What changed is how often a single tick froze for a long time.
- With Kapok the explorers covered slightly more ground in every pack (the runs overlap in All the Mons), so the server was not doing less work.

The long ticks have the same length as the full garbage collections in these runs, so the smaller startup heap is the likely reason for the difference. A server with more memory than it needs will see fewer of these freezes and less difference from Kapok.

### Normal load

The chunk features were also measured on their own under lighter load, four players at 10 blocks per second, in 26 sessions across the three packs. Tick times with and without them were the same.

## Compatibility and limits

Kapok changes where waits happen; it does not make the work cheaper. It starts no threads of its own, does not change render distance, and does not raise frame rate. Nothing about the world or the saves changes.

With c2me installed, chunk loading stays with c2me and Kapok's chunk features step aside. The map preview change still applies.

Kapok is not a replacement for Sodium, Lithium, FerriteCore or ModernFix. It waits in places those mods do not cover, so it is meant to be installed alongside them.

<p><a href="https://www.patreon.com/KURONAMI333"><img src="https://media.forgecdn.net/attachments/description/1564253/description_0b5d69f3-bf0d-4a38-8894-6883abaa65e9.png" width="250" height="100" alt="Support my mods on Patreon"></a> <a href="https://x.com/kuronami333"><img src="https://media.forgecdn.net/attachments/description/1564253/description_0ed421f9-f6e1-4334-8e1e-fac25c804fe6.png" width="250" height="100" alt="Follow @kuronami333 on X"></a> <a href="https://www.youtube.com/channel/UCoimycJCVZmxNKyX9Vr7Apg"><img src="https://media.forgecdn.net/attachments/description/1564253/description_b953be14-e0c1-4290-b5d3-5ae75107ce90.png" width="250" height="100" alt="Watch KURONAMI on YouTube"></a></p>

<p><img src="https://media.forgecdn.net/attachments/description/1537959/description_582d8b5a-1840-40ee-b403-c361b7f74006.png" alt="Need a custom mod?" width="776" height="95"></p>

<p align="center" style="text-align: center;">Paid commissions for videos, streams and servers. Send me your idea: <a href="mailto:kuronami.mods@gmail.com">kuronami.mods@gmail.com</a></p>

<p><img src="https://media.forgecdn.net/attachments/description/1537959/description_a7712950-22d2-40cb-94f6-b8edc51870b9.png" alt="" width="776" height="56"></p>

All Rights Reserved. Modpack inclusion is allowed without permission or credit.

Bugs and questions: comment on the CurseForge page, or DM @kuronami333 on X.

[Source](https://github.com/KURONAMI333/kapok)
