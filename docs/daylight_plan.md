# HG Daylight: plan

A native Android (Kotlin) remake of *Pokémon SoulSilver* for the Daylight DC-1, built the way DQ Daylight
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

Status (2026-10-17): the field renderer works. `android/core` reads the SoulSilver ROM's NitroFS, NARCs,
map matrix, land data, area data, map and prop textures (all 7 DS texture formats), BMD0 models (SBC, nodes,
materials, GX display lists) and the area light files, and renders a field view in the original's colours and
in the Daylight style: `./gradlew :tools:renderMap -Pargs="--header 60"` (New Bark Town) writes PNGs to `out/`.

## Built so far (2026-10-17)
The walk-around demo on the DC-1 (`./gradlew :app:installFast` from `android/`, app id `dev.hgdaylight.ss`):
- the field from the ROM in the Daylight style (fitted grays, ink, 1.5 px outlines, steady camera), 60 fps;
- the hero (hand-toned) walking with the decomp's timing, collision, ledges and BDHC heights;
- the map's people and objects standing in place (zone events, the sprite table in overlay 1);
- doors and exit mats warping between maps (the map header table in the ARM9 binary), with a black fade;
- the field touch menu as a panel docked in a corner (drag to another corner, double-tap to fold): its BG,
  the seven icons (cells from a/0/1/4) and their labels (message bank 196 in the ROM's font 0), in the UI's
  four tones with black ink labels; a press shows the icon's highlighted palette.

Next, in order:
1. What the touch menu's icons open (Pokédex, Trainer Card, Pokémon, Save, Bag, Options, Pokégear), and its
   right-hand buttons (registered items, running shoes: char 70, cells 68/69).
2. Talking: A on an NPC or a sign (bg events) runs its script; needs the message decoder (`files/msgdata`) and
   the event-script VM (`src/scrcmd*.c`).
3. NPC movement types (wander, look around) and event flags (who is hidden at the start).
4. Art touch-ups by hand: striped roofs, inked grass tufts as in the mockup, the windmills (thin geometry
   that the outline pass turns into broken lines).
5. Settings sheet (DQ's): steady camera, line width, panel scale, tone presets.

## Owner decisions (2026-10-17)
- **SoulSilver only** (`rom/soulsilver.us.nds`, `sha1 f8dc38ea20c17541a43b58c5e6d18c1732c7e582`).
- **The look** (owner's mockup of New Bark Town): no big changes to the game; change how it renders: solid
  black outlines where they make sense, shading retuned for a semi-low-contrast grayscale panel.
- **The top screen's view fills the portrait screen.** The DS bottom screen becomes a panel the player can
  dock in any corner (bottom left, bottom right, top left, top right).
- **Movement as in DQ Daylight:** the invisible joystick anywhere on the screen.

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

## 3. Display

DC-1 app window: 1184x1584 (Sol:OS override over the 1200x1600 panel).

- **Steady camera** (default, *not in the original*; owner, 2026-10-17: "looks better, easier on the eyes", accepting the lost perspective): a parallel projection at 1 unit per canvas pixel, its target snapped to whole pixels, so walking scrolls the picture by whole pixels (no shimmer) and 32-unit sprites are exactly 32 px; the original perspective is an option.
- **Field canvas: 296x396 at 4x**, edge to edge. 4x is the scale at which the DS's full 256-px width fits
  (5x gives 236 px, narrower than the original). The 3D camera keeps the original's pixel density: preset 0
  (`src/field/field_camera.c`: distance 666.9, x angle 0xDD62, half fovy 0x5C1) with its 192 rows' density,
  so the taller canvas shows more of the map above and below the player, never a zoomed picture.
- **Renderer:** the original's 3D field, drawn the DS way (nearest texels, the material's repeat/flip,
  modulate, per-vertex DS lighting from the area light file, alpha-cut texels), then the Daylight pass
  (`render/DaylightStyle.kt`):
  - luminance to gray with input levels and a gamma that darkens the mid-tones (grass, roofs) and keeps
    paths light;
  - solid black 1-px outlines where one object stands in front of another, where polygon ids differ (the
    DS's own edge marking, `sAreaEdgeColorTable` in `src/field/area_data.c`, which the original draws in
    dark gray 0x1084), and at depth creases within an object;
  - dark rims on the ground where a pixel is much darker than its neighbour (the grass fringes).
- **Bottom-screen panel, corner-docked:** open question: its size. The DS bottom screen at 4x is
  1024x768 (most of the width); 2x is 512x384 (a third of the width, touch targets about 6 mm); 3x is 768x576.
- **Tones:** DQ's presentation presets (Standard, Crisp, Low light, Custom, Calibrate) on top.

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
- The docked panel's size (2x, 3x, collapsible to a tab?).
- Scope of the first playable: one route and a wild battle, or New Bark Town to Violet City?
