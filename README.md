# HyPerks

HyPerks is a Hytale server mod foundation for VIP cosmetics with:

- category-based cosmetics (`auras`, `footprints`, `floating_badges`, `trophy_badges`)
- permission nodes per cosmetic
- player cosmetic selection persistence
- multi-language support (`en`, `fr`)

## Commands

- `/hyperks menu`
- `/hyperks list [category]`
- `/hyperks equip <category> <cosmeticId>`
- `/hyperks unequip <category>`
- `/hyperks active`
- `/hyperks lang <en|fr>`
- `/hyperks status`
- `/hyperks reload` (requires `hyperks.admin.reload`)

## Permission model

- `hyperks.use`
- `hyperks.admin.reload`
- `hyperks.cosmetic.<category>.<id>`
- `hyperks.cosmetic.<category>.*`
- `hyperks.cosmetic.*`

## Runtime files

HyPerks writes its data to:

- `mods/HyPerks/config.json`
- `mods/HyPerks/cosmetics.json`
- `mods/HyPerks/lang/en.json`
- `mods/HyPerks/lang/fr.json`
- `mods/HyPerks/players/<uuid>.json`

## Runtime renderer

HyPerks includes an in-game runtime renderer for:

- auras
- footprints
- floating badges
- trophy badges

`config.json` keys:

- `runtimeRenderingEnabled` (`true`/`false`)
- `runtimeRenderIntervalMs` (50 to 5000)
- `debugMode` (`true`/`false`)

`cosmetics.json` entries can define:

- `effectId` (particle system id)
- `renderStyle` (ex: `orbit`, `wings`, `hearts`, `steps`, `badge`, `crown`)

## Custom HyPerks Assets

This project now bundles a first custom asset pack inside `HyPerks.jar`:

- `Common/Particles/Textures/HyPerks/*` (badge/trophy textures)
- `Server/Particles/HyPerks/Badges/*` (VIP badge particle systems)
- `Server/Particles/HyPerks/Trophies/*` (trophy/crown particle systems)

Default `floating_badges` and `trophy_badges` cosmetics are wired to these custom assets.

### Rank Tag Streams

`floating_badges` now includes rank streams that emit 7-10 mini rank tags from the player base upward:

- `vip`
- `vip_plus`
- `mvp`
- `mvp_plus`

Permission nodes:

- `hyperks.cosmetic.floating_badges.vip`
- `hyperks.cosmetic.floating_badges.vip_plus`
- `hyperks.cosmetic.floating_badges.mvp`
- `hyperks.cosmetic.floating_badges.mvp_plus`

## Build

On Windows:

```powershell
.\gradlew.bat build
```

Output jar:

- `build/libs/HyPerks.jar`
