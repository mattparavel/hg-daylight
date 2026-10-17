# HG Daylight: plan

A version of *Pokémon HeartGold* made for the Daylight DC-1, built from the `ctrl-cheeb-del/pokeheartgold`
decomp (forked here as `mattparavel/hg-daylight`). It follows the DQ Daylight principles
(`~/dq-daylight/docs/dc1_product.md`): inside the picture the game stays the original; everything around it
(layout, input, tones, fades, pause/resume) is designed for the DC-1. Any change to what the player
experiences is an option, off by default, captioned *Not in the original*.

Status: plan only (2026-10-17). Nothing below is built yet.

## 0. What is different from DQ Daylight

| | DQ Daylight | HG Daylight |
|---|---|---|
| Source | Game Boy ROM, reverse-engineered | Full C decomp, byte-matching `pokeheartgold.us.nds` |
| Runtime | Native Kotlin remake | The DS game itself: an emulator core, or (much later) a native port |
| Screens | One 160x144 | Two 256x192 (top + touch bottom), 3D overworld |
| Assets | Decoded from the user's ROM | In the repo (`files/`: 3,400 PNG, 577 NCLR, 832 NSBTX) |
| Grayscale | 3,584 hand-edited 4-shade grids | Too many assets to hand-edit all; palette-level remap plus hand edits where meaning depends on colour |
| Build | Gradle | MWCC 2.0/sp2p2 + Nitro SDK 3.2 through Wine (proprietary, supplied by the owner) |

**No ROM bytes in the APK** (the DQ rule) maps naturally: ship a **BPS patch** from the retail HeartGold ROM
(`sha1 4fcded0e2713dc03929845de631d0932ea2b5a37`) to the Daylight build. The player imports their own ROM once,
the app checks the SHA-1 and applies the patch on the device.

## 1. The screen

DC-1 app window: **1184x1584** (Sol:OS override over the 1200x1600 panel; `adb shell wm size`).

| Layout | Native | Scale | On screen | Leftover |
|---|---|---|---|---|
| **Stacked** (top over bottom) | 256x384 | **4x** | 1024x1536 | 80 px each side, 24 px top/bottom |
| One screen, portrait | 256x192 | 4x | 1024x768 | 816 px below for controls |
| Stacked, rotated (book) | 384x256 | 4x | 1536x1024 in landscape | 24/280 px |

**Stacked at 4x is the natural DC-1 layout**: both DS screens, edge to edge, one whole-number pixel scale,
no filtering. A 16-px overworld tile is 64 device px (8.5 mm). 5x (DQ's density) does not fit 256 px across
(1280 > 1184). The bottom screen is real touch at 4x, so HGSS's own touch UI (battle menu, bag, party,
Pokégear, the field touch menu) works directly with a finger: the game was already designed for this.

The 80-px side gutters hold nothing by default (paper). Optional: the ≡ chip and the x2 pill in a gutter.

## 2. Input

HGSS already does almost everything on the touch screen. What is missing on a DC-1 with no buttons:

- **Movement:** DQ's hidden floating joystick, but only over the **top** screen (the bottom screen's touches
  belong to the game). A tap on the top screen = A; two-finger tap anywhere = B.
- **Running:** *Not in the original*, option "Always run" (off): hook `PlayerAvatar_CheckRunningShoesLock`
  in `src/field/field_control.c:124`, which already ORs B into the held keys.
- **START / SELECT / X / Y / L / R:** X opens the menu, which the bottom-screen touch menu already covers;
  Y (registered item) also has a bottom-screen button. Remaining keys go on a slim pill row in the gutter or
  under the stack.
- **Back key and orange keys = B**, long press opens Settings, Back never quits (DQ decisions 1 and 4a).
- **Stylus:** the Wacom pen maps 1:1 to the DS stylus on the bottom screen. Barrel button = B.
- **Pad / keyboard:** DQ decision 10, plus L/R/X/Y/SELECT, which DQ never needed.

## 3. Tones and art (ROM side, in this repo)

The DC-1 shows gray only. Two layers:

1. **Presentation LUT (device side):** the DQ tone presets (Standard 00/5A/B4/FF, Crisp, Low light,
   Custom, Calibrate) applied to a perceptual luminance of the DS's 15-bit colour, with a contrast curve.
2. **Semantic fixes (ROM side), where colour carries meaning:**
   - HP bars (green/yellow/red): add a pattern or tick marks so the third is readable without colour.
   - Gender symbols, shiny star, status badges, type labels: already text or shape; check contrast.
   - Text boxes and fonts: black text on white, no tinted window frames under the text.
   - Overworld 3D textures (`NSBTX`): walkable vs blocking contrast in gray (DQ's epaper.md §1 rules: light
     ground, dark walls, mid-gray water, full-contrast landmarks), starting with routes, towns and caves.
   - Tall grass must read clearly against paths: the most important tile in the game.
   - Pokémon sprites: palette-level remap first, then hand fixes where a sprite turns to mud.
3. **Fades:** 75 `BeginNormalPaletteFade` calls fade through white (`RGB_WHITE`). *Black fades*, as in DQ,
   default on (owner's 2026-10-16 rule for DQ), through one switch in `src/screen_fade.c`.
4. **Flashes:** a check of the battle and move animations for strobes (DQ decision 13).

The tooling should mirror DQ's: a sheet renderer (`tools/daylight/art.py`) that previews any palette or
texture in DC-1 tones, and a gate that diffs the build against a gray preview of the original.

## 4. Runtime: three options

| | What | Effort | Fit to DC-1 |
|---|---|---|---|
| **A. Patch + melonDS app** | Build the patched ROM; play it in the installed melonDS (`me.magnum.melonds`) with a 4x stacked custom layout | Small | Partial: no tone LUT, no top-screen joystick, generic UI |
| **B. HG Daylight app** | A Kotlin app (DQ's shell: GameView, FramePacer, tone LUT, touch grammar, Settings sheet, AtomicFile, AudioOut, installFast) around the **melonDS core** (C++, GPL-3) via JNI; imports the ROM and applies the patch | Medium | Full: everything in §1 and §2 |
| **C. Native port** | Compile the decomp for ARM64 Android with a reimplemented Nitro SDK (GX 2D + 3D, sound, FS, ARM7) | Very large | Full, plus layout freedom inside the game |

Recommendation: **A now to play and judge the art, B as the product.** C only if a later need (re-laying out
the game's own screens) cannot be met by ROM changes plus B.

## 5. First steps

1. Owner supplies `mwccarm.zip` and `NitroSDK-3_2-060901.7z`; run `scripts/setup-macos.sh` with
   `POKEHEARTGOLD_TOOLCHAIN_DIR`, then `./scripts/build.sh` and confirm the matching SHA-1.
2. Add a `DAYLIGHT=1` build flag so the matching build stays available (`compare` must keep passing without it).
3. First ROM changes behind the flag: black fades, Always run, text box contrast.
4. Build a BPS patch; set up melonDS on the DC-1 with a 4x stacked layout; play and take screencaps.
5. Mockups of the gray art (tall grass, routes, battle UI) before editing assets at scale.
6. Start the B app from DQ's shell, with its own applicationId (FE digest gotcha: never `dev.dwremake`).

## Open questions for the owner
- Runtime: A then B, as recommended?
- Layout: stacked 4x by default, or one screen at a time?
- HeartGold only, or SoulSilver too (the decomp builds both)?
- Always run: an option, or on by default?
