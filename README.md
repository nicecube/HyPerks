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

## Build

On Windows:

```powershell
.\gradlew.bat build
```

Output jar:

- `build/libs/HyPerks.jar`
