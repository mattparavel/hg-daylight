# HG Daylight: plan

A native Android (Kotlin) remake of *Pokémon HeartGold* for the Daylight DC-1, built the way DQ Daylight
(`~/dq-daylight`) was: the app ships **no game data**, the player imports their own ROM once, and maps,
graphics, text, scripts, sound and battle data are decoded from it on the device. Game logic is
reimplemented natively in Kotlin.

The difference from DQ: the reverse engineering is already done. This repo (forked from
`ctrl-cheeb-del/pokeheartgold`) is a complete, byte-matching C decompilation, so it serves as the
**specification**: every formula, script command and file format is readable source. We never build the
ROM from it, so the Metrowerks compiler and the Nitro SDK are not needed.

Principles carried over from `~/dq-daylight/docs/dc1_product.md`: inside the game, behaviour matches the
original; layout, input, tones and pause/resume are designed for the DC-1; anything that changes what the
player experiences is an option, off by default, captioned *Not in the original*.

Status: plan only (2026-10-17).

## 1. How the decomp is used

- **Reference source:** port behaviour from `src/` (C) to Kotlin, function by function, citing the C file.
- **File formats:** the repo's tools document every format the ROM uses: `tools/nitroarc` (NARC),
  `tools/nitrogfx` (NCGR/NCLR/NCER/NSCR), `tools/msgenc` (message text), plus the JSON/CSV sources under
  `files/` that show what each binary table means.
- **Golden tests without a DS compiler:** the pure-logic C (damage calc, stat calc, RNG, experience,
  catch rate) can be compiled with the Mac's own clang into a small host test harness. Kotlin results are
  compared against it, the way DQ compared Kotlin decoders against Python references.
- **Decoder tests:** the repo's `files/` holds every asset as source (PNG, JSON, text). A Kotlin decoder
  reading the user's ROM must produce the same thing as the matching file in `files/`.

## 2. Subsystems and size

| Subsystem | Decomp reference | Notes |
|---|---|---|
| ROM filesystem | NitroFS + NARC (`tools/nitroarc`) | First thing to build |
| 2D graphics | NCGR/NCLR/NCER/NSCR (`tools/nitrogfx`) | Sprites, menus, battle UI |
| Text | `files/msgdata`, `tools/msgenc` | Encrypted message banks; `charmap.txt` |
| Pokémon / moves / items data | `files/poketool/personal`, `waza`, `trainer` | Tables |
| Battle engine | `src/battle/` (32 files), `files/battledata/script` | Battle scripts VM; largest logic port |
| Field scripts | `src/scrcmd*.c`, `src/data/fieldmap/script_cmd_table.h` (~860 commands) | Like DQ's 128-opcode VM, much bigger |
| Overworld | `files/fielddata` (map matrix, events), NSBMD/NSBTX 3D models | 3D in the original; see §3 |
| Sound | SDAT (SSEQ/SBNK/SWAR) | Needs a DS sequencer + synth in Kotlin, as DQ ran its GB driver on an APU synth |
| Saves | `src/save*.c` | Own format, AtomicFile like DQ |

## 3. Display (open decision)

DC-1 window: 1184x1584. A remake is not tied to the DS's two 256x192 screens, so the layout can be
designed like DQ's portrait canvas:

- **One portrait canvas** at a whole-number scale (4x gives 296x396 px of game; 5x gives 236x316). The
  field view on top, a touch panel below in place of the DS bottom screen.
- **Overworld:** the original is 3D (tilted camera over tile-based maps). Options: render the 3D models
  with OpenGL ES at a fixed tilt; or a top-down 2D render from the map's tile and collision data with
  hand-tuned gray tiles (closer to DQ's look, much simpler, not the original's picture).
- **Tones:** DQ's tone LUT and presets. Grayscale art: palette-level remap plus hand fixes where colour
  carries meaning (HP bars, tall grass vs path, status badges).

## 4. Carried over from DQ Daylight

Shell pieces to reuse (with a new applicationId, never `dev.dwremake`): `GameHost`, `RomImport` (SHA-1 check
for `pokeheartgold.us.nds`, `4fcded0e2713dc03929845de631d0932ea2b5a37`), `FramePacer`, the tone LUT and
Calibrate, `PlayerSettings` and the Settings sheet, `AtomicFile`, `SaveArchive`, the audio output shell, the
touch grammar (joystick, tap = A, two-finger B, Back = B), black fades, Gradle `installFast`.

## 5. First milestone

1. Owner supplies a dump of their HeartGold cartridge (`rom/`, git-ignored).
2. Android project skeleton from DQ's shell; ROM import with the SHA-1 check.
3. Kotlin NitroFS + NARC reader; golden test: every file matches `files/`.
4. Text decoder; golden test against `files/msgdata`.
5. 2D graphics decoder; render a Pokémon front sprite and a menu window in DC-1 tones.
6. Mockups of the field and battle screens on the DC-1 before building them.

## Open questions for the owner
- Overworld: real 3D, or top-down 2D?
- HeartGold only, or SoulSilver too?
- Scope of the first playable: one route and a wild battle, or New Bark Town to Violet City?
