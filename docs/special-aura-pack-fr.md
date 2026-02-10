# Pack Aura Special (AngelWings+)

Ce pack ajoute 4 nouvelles auras:

- `fire_ice_cone`: cone double-spirale feu/glace autour du joueur.
- `storm_clouds`: nuages de pluie avec impacts d'eclairs periodiques.
- `wingwang_sigil`: signe WingWang anime devant le joueur.
- `fireworks_show`: feux d'artifices au-dessus de la tete.

## Ressources creees

- Textures:
  - `assets/Common/Particles/Textures/HyPerks/Aura_FireIce_Cone.png`
  - `assets/Common/Particles/Textures/HyPerks/Aura_Storm_CloudBolt.png`
  - `assets/Common/Particles/Textures/HyPerks/Aura_WingWang_Sigil.png`
  - `assets/Common/Particles/Textures/HyPerks/Aura_Fireworks_Burst.png`
- Systems:
  - `assets/Server/Particles/HyPerks/Auras/Fire_Ice_Cone.particlesystem`
  - `assets/Server/Particles/HyPerks/Auras/Storm_Clouds.particlesystem`
  - `assets/Server/Particles/HyPerks/Auras/WingWang_Sigil.particlesystem`
  - `assets/Server/Particles/HyPerks/Auras/Fireworks_Show.particlesystem`
- Spawners:
  - `assets/Server/Particles/HyPerks/Auras/Spawners/HyPerks_Aura_Fire_Ice_Cone.particlespawner`
  - `assets/Server/Particles/HyPerks/Auras/Spawners/HyPerks_Aura_Storm_Clouds.particlespawner`
  - `assets/Server/Particles/HyPerks/Auras/Spawners/HyPerks_Aura_WingWang_Sigil.particlespawner`
  - `assets/Server/Particles/HyPerks/Auras/Spawners/HyPerks_Aura_Fireworks_Show.particlespawner`

## Regeneration des textures

Script dedie:

```powershell
.\scripts\generate_special_fx_textures.ps1
```

## Tuning rapide (qualite/perf)

- Plus "cinematic":
  - augmenter `MaxConcurrentParticles` de +2 a +4
  - augmenter `ParticleLifeSpan.Max` de +0.10 a +0.20
- Plus lisible en hub charge:
  - baisser `SpawnRate.Max` de -1
  - baisser `LightInfluence` de 0.05
- Plus performant:
  - augmenter les gates runtime (`frame % N`) dans `HyPerksCoreService`
  - reduire les boucles de points (cone, storms, fireworks)
