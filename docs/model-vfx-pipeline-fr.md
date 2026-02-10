# Pipeline VFX 3D Premium (HyPerks)

Ce document decrit le pipeline recommande pour passer des rigs HyPerks de placeholders vers de vrais assets 3D premium.

## Objectif

- Utiliser des models custom `.blockymodel` + animations `.blockyanim`.
- Garder les IDs JSON stables deja relies au code HyPerks.
- Valider rapidement les assets avant release.

## IDs JSON utilises par le runtime

Le code attend ces assets JSON serveur:

- `Server/Models/HyPerksVFX/FireIceCone_Rig.json`
- `Server/Models/HyPerksVFX/StormClouds_Rig.json`
- `Server/Models/HyPerksVFX/WingWangSigil_Rig.json`
- `Server/Models/HyPerksVFX/FireworksShow_Rig.json`

Le backend multi-part cherchera automatiquement ces variantes si elles existent:

- `*_Core.json`, `*_HelixFire.json`, `*_HelixIce.json`
- `*_Bolt.json`, `*_Ring.json`
- `*_Inner.json`, `*_Mid.json`, `*_Outer.json`
- `*_Launcher.json`, `*_Burst.json`

Si une variante n'existe pas, le runtime fallback sur le JSON base.

## Workflow recommande

1. Modeler les meshes dans ton outil 3D (Blender, etc.).
2. Exporter via ton pipeline Hytale vers `.blockymodel` et `.blockyanim`.
3. Placer les fichiers dans `assets/Server/Models/HyPerksVFX/`.
4. Mettre a jour chaque JSON pour pointer vers tes fichiers custom:
   - `Model`
   - `Texture`
   - `AnimationSets`
5. Tester in-game:
   - `/hyperks debugmodels hyperksvfx`
   - `/hyperks debugmodel Server/Models/HyPerksVFX/FireIceCone_Rig_Core.json`
6. Ajuster budget/LOD en live:
   - `/hyperks modelvfx budget <n>`
   - `/hyperks modelvfx lodultra <n>`
   - `/hyperks modelvfx radius <blocs>`
   - `/hyperks modelvfx interval <ms>`
   - `/hyperks modelvfx audit`
   - `/hyperks modelvfx density` (diagnostic densite locale autour du joueur)

## Regles qualite

- Eviter les references vanilla `Items/Projectiles/*` pour les effets premium finaux.
- Garder des hitboxes compactes et coherentes avec la taille reelle.
- Fournir au moins une animation bouclee (`Idle` ou `Loop`) par rig principal.
- Limiter le nombre de parts actives pour rester dans le budget `maxRigsPerPlayer`.
- Le runtime applique maintenant un LOD de proximite:
  - `lodUltraMaxWorldPlayers` controle ULTRA/BALANCED selon densite locale.
  - `lodNearbyRadius` definit le rayon (en blocs) utilise pour mesurer cette densite.

## Audit local

Script disponible:

- `scripts/audit_model_vfx_assets.ps1`
- `scripts/generate_model_vfx_premium_templates.ps1`
- `scripts/stage_model_vfx_premium.ps1`
- `scripts/generate_model_vfx_custom_assets.ps1`
- `scripts/apply_model_vfx_premium_pack.ps1`

Il detecte:

- Fichiers JSON rig manquants
- References placeholder vanilla
- Champs JSON critiques absents (`Model`, `Texture`)

Commande:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/audit_model_vfx_assets.ps1
```

Verifier aussi les references templates premium:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/audit_model_vfx_assets.ps1 -CheckPremiumTemplates
```

Generer des templates JSON premium (sans toucher les JSON runtime):

```powershell
powershell -ExecutionPolicy Bypass -File scripts/generate_model_vfx_premium_templates.ps1
```

Sortie:

- `docs/model-vfx-templates/*.template.json`
- `docs/model-vfx-templates/_premium_manifest.csv`

Preparer la structure premium attendue (folders + `_EXPECTED_FILES.txt`):

```powershell
powershell -ExecutionPolicy Bypass -File scripts/stage_model_vfx_premium.ps1
```

Mode simulation:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/stage_model_vfx_premium.ps1 -DryRun
```

Application optionnelle des refs premium aux JSON runtime (a faire quand les assets existent):

```powershell
powershell -ExecutionPolicy Bypass -File scripts/stage_model_vfx_premium.ps1 -ApplyToRuntimeJson -RequireAssetsPresent
```

Generation d'un pack premium 100% custom (textures + `.blockymodel` + `.blockyanim`, sans reutiliser les assets vanilla):

```powershell
powershell -ExecutionPolicy Bypass -File scripts/generate_model_vfx_custom_assets.ps1 -CleanExisting
```

Pipeline recommande pour un refresh complet:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/generate_model_vfx_premium_templates.ps1
powershell -ExecutionPolicy Bypass -File scripts/generate_model_vfx_custom_assets.ps1 -CleanExisting
powershell -ExecutionPolicy Bypass -File scripts/stage_model_vfx_premium.ps1 -ApplyToRuntimeJson -RequireAssetsPresent
powershell -ExecutionPolicy Bypass -File scripts/audit_model_vfx_assets.ps1 -CheckPremiumTemplates -FailOnWarnings
```

Application automatique d'un pack premium depuis `Assets.zip` (copie + patch des 15 JSON runtime + templates):

```powershell
powershell -ExecutionPolicy Bypass -File scripts/apply_model_vfx_premium_pack.ps1
```

Mode strict (echoue si warnings premium restants):

```powershell
powershell -ExecutionPolicy Bypass -File scripts/audit_model_vfx_assets.ps1 -FailOnWarnings
```

## Release stricte

Tu peux forcer la pipeline release a verifier les assets modeles:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/release.ps1 -Version 0.1.0 -StrictModelVfx
```

Optionnel: forcer le jar serveur Hytale utilise au build release:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/release.ps1 -Version 0.1.0 -StrictModelVfx -HytaleServerJar ..\SharedRuntime\HytaleServer.jar
```
