# HyPerks

HyPerks is a Hytale server mod for VIP cosmetics with:

- categories: `auras`, `auras_premium`, `trails`, `footprints`, `floating_badges`, `trophy_badges`
- permission nodes per cosmetic
- multi-language support (`en`, `fr`)
- runtime renderer for continuous effects
- anti-spam command cooldown
- permission cache with manual refresh
- persistence modes: `json`, `sqlite`, `mysql`
- bundled custom asset pack (V2)

## Commands

- `/hyperks menu`
- `/hyperks list [category]`
- `/hyperks equip <category> <cosmeticId>`
- `/hyperks unequip <category>`
- `/hyperks active`
- `/hyperks lang <en|fr>`
- `/hyperks refreshperms`
- `/hyperks status`
- `/hyperks reload` (requires `hyperks.admin.reload`)

## Permissions

- `hyperks.use`
- `hyperks.admin.reload`
- `hyperks.admin.cooldown.bypass`
- `hyperks.admin.permission.refresh`
- `hyperks.cosmetic.<category>.<id>`
- `hyperks.cosmetic.<category>.*`
- `hyperks.cosmetic.*`

## Data Files

HyPerks writes runtime data to:

- `mods/HyPerks/config.json`
- `mods/HyPerks/cosmetics.json`
- `mods/HyPerks/lang/en.json`
- `mods/HyPerks/lang/fr.json`
- `mods/HyPerks/players/<uuid>.json` (json mode)
- `mods/HyPerks/player-state.db` (sqlite mode)

## Config

Default `config.json` includes:

```json
{
  "defaultLanguage": "en",
  "worldWhitelist": ["default"],
  "allowInAllWorlds": false,
  "autoShowMenuHintOnJoin": true,
  "runtimeRenderingEnabled": true,
  "runtimeRenderIntervalMs": 250,
  "commandCooldownMs": 1200,
  "permissionCacheTtlMs": 1500,
  "detailedCosmeticDescriptions": true,
  "menu": {
    "guiEnabled": true,
    "guiFallbackToChat": true,
    "guiCommandSeed": "hyperks"
  },
  "persistence": {
    "mode": "json",
    "jdbcUrl": "",
    "username": "",
    "password": "",
    "tableName": "hyperks_player_state",
    "connectTimeoutMs": 5000,
    "autoCreateTable": true
  },
  "debugMode": false
}
```

Persistence notes:

- `mode: "json"` uses one file per player
- `mode: "sqlite"` can use default local DB (`player-state.db`) or custom `jdbcUrl`
- `mode: "mysql"` requires a valid MySQL JDBC URL

## Runtime Renderer

The runtime renderer supports all categories and applies world filtering from config.

`renderStyle` examples in `cosmetics.json`:

- auras: `orbit`, `wings`, `hearts`
- auras_premium: `orbit`, `crown`, `pillar`
- trails: `stream`, `spark`, `spiral`
- footprints: `steps`
- floating_badges: `rank_stream`
- trophy_badges: `crown`

## Asset Pack V2

This build bundles custom assets from `assets/` directly in `HyPerks.jar`:

- rank tag streams (VIP, VIP+, MVP, MVP+)
- trophy badge effects
- premium aura effects
- movement trail effects

## Build

```powershell
.\gradlew.bat build
```

Output jar:

- `build/libs/HyPerks.jar`

## Release Automation

A local pipeline script is included:

```powershell
.\scripts\release.ps1 -Version 0.3.0 -Repo nicecube/HyPerks
```

The script can:

- run build
- create/update tag (`v<Version>`)
- push `main` and tag
- publish GitHub release with uploaded jar (requires `gh` CLI)
