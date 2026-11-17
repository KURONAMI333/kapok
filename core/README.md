# Kapok Core

> The startup half of Kapok, and the jar that ships. Reads every mod class once at startup instead of twice, and from the second launch, not at all.

Minecraft 1.21.1 / NeoForge 21.1.x

This directory is the `core` subproject of the Kapok build. Its output,
`core/build/libs/kapok-<version>.jar`, is the one file a player installs: a loader
service jar that carries the Kapok mod (the root project's jar) inside it under
`META-INF/kapok/` and introduces it to mod discovery through `KapokBundleLocator`.
A jar that declares a loader SPI is placed in FML's SERVICE layer and excluded from the
mod scan, so this is the only shape in which the reader and the mod can be one download.
The bundle is materialised under `.cache/kapok-core/bundle/` in the game directory so
the loader sees a regular file; `-Dkapok.core.bundle=off` stands the locator down for
development launches where the mod already comes from the classpath.

---

## What it does

When NeoForge scans your mods at startup it walks every `.class` file in every mod jar
twice. The first pass is the ASM scan, which the loader genuinely needs. The second pass
calls `Files.readAllBytes` on the same entry, hands the bytes to the signature verifier,
and — for an unsigned jar, which is nearly every mod — throws them away without recording
anything. Kapok Core skips that second pass.

Nothing is disabled and nothing is traded away. On an unsigned jar the verifier is a
constant function: it returns `UNVERIFIED` no matter what the bytes are. Signed jars are
left completely alone, and the signature check that runs when a class is actually loaded
is untouched — it takes a different path through the loader and never sees this code.

There is no config. There is nothing to tune.

## It only reads your mods once

The loader also parses every class in every mod jar with ASM on every launch, to find the
annotations that tell it what each mod contains. That result is a pure function of the jars
you have installed, so Kapok Core writes it down the first time and reads it back on
every launch after that. That is every class in every installed mod that is never parsed
again until you add, remove, or update a mod.

The stored scan is keyed on what is actually in each jar, read from the zip index without
unpacking anything. Change a jar and its entry is rebuilt; nothing else is. If a stored
scan cannot be read back for any reason, the loader scans that jar the way it always did,
and the log says which jar and why. Deleting the `.cache/kapok-core` folder in your game
directory throws the whole thing away and costs you one slower launch.

## Jars inside jars live on disk, not in memory

Many mods ship their libraries inside themselves, and a jar mounted from inside another
jar is not a file the operating system knows about — so nothing can read a part of one.
Java inflates the whole nested jar into memory and keeps it there for the rest of the
session, whether or not anything is read from it again. In a large modpack that is many
nested jars held in heap that exist only because the bytes had nowhere else to live.
Kapok Core writes them out under `.cache/kapok-core/nested/` in your game
directory the first time it sees them, and the loader opens them as ordinary files: the
operating system pages in what is asked for and nothing beyond what is asked for is held.
On disk they come to roughly what they already occupy inside the mods that carry them.

Which nested jars exist and which version of a duplicated library wins are still decided
entirely by the loader; only where the bytes are read from changes. A copy is written
again when the mod carrying it changes, and copies belonging to mods you no longer have
are removed at the end of startup. Deleting the `.cache/kapok-core` folder throws all of
this away too and costs you one slower launch. If mod discovery is not the shape this
expects — a NeoForge line it was not built for, a loader that no longer carries the
Jar-in-Jar locator, a game directory it cannot write to — it says so in one line and the
nested jars are mounted the way they always were.

## Installation

Drop `kapok-<version>.jar` in `mods/`. That is all; the mod half comes with it.

It works on a dedicated server as well as a client. The double read happens in the mod
loader, and a server runs that same loader, so the jar goes in the server's `mods/`
folder with the same result.

**This half will not appear in the Mods screen.** It has no mod id and no
`neoforge.mods.toml` — it is a loader service, not a mod, because the work it does
happens before the mod list exists. The bundled mod half appears as Kapok. That is
expected, not a broken install.

## How to tell it is working

One line near the top of `logs/latest.log`:

```
[KapokCore/]: Kapok Core active on NeoForge 21.1.231: mod classes will be read once at startup instead of twice
[KapokCore/]: Kapok Core wrapped its first mod file: .../mods/some-mod.jar
[KapokCore/]: Kapok bundle: loaded kapok-mod-0.1.0.jar from kapok-0.1.0.jar
[KapokCore/]: Kapok Core: jar-in-jar locator replaced (nested jars are written to .cache/kapok-core/nested and read from there)
[KapokCore/]: Kapok Core: nested jars on disk instead of in memory - 141 written, 116 reused, 0 mounted in place
```

If it decided to switch itself off, it says so on the same line and names the reason.

## When it switches itself off

Kapok Core claims every mod jar in order to wrap it, so it refuses to run whenever it
cannot be sure that is safe. It checks once, on the first jar it is offered, and the
answer holds for the rest of the launch:

- the running NeoForge version is not on the 21.1 line
- another mod also registers an `IModFileReader`
- the check cannot find Kapok Core's own reader, so it cannot be trusted about others
- inspecting the loader threw
- reading a mod file threw

In every one of those cases it stops claiming jars, so mod files are read and scanned the
way the loader does without Kapok, and startup simply takes as long as it used to. The
nested-library change is decided separately and can stay active.

## License

[All Rights Reserved](LICENSE). The source is published so it can be read and checked.

## Credits

- Author: KURONAMI
