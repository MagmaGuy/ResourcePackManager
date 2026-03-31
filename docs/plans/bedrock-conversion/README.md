# Bedrock Conversion for GeyserMC — Plan Overview

> Feature: Automatically convert merged Java resource packs into Bedrock format for GeyserMC.
> Branch: `feature/bedrock-conversion`
> Status: Planning complete, ready for implementation

## What This Does

After ResourcePackManager merges all Java resource packs, it generates:
1. A **Bedrock resource pack** (`.zip`) with item textures for Geyser's `packs/` folder
2. **Geyser custom item mappings** (`.json`) for Geyser's `custom_mappings/` folder

This is **100% Geyser-focused**. The output only works through GeyserMC.

## Documents

| Document | Purpose |
|----------|---------|
| [Design Plan](2026-03-30-bedrock-conversion-design.md) | Architecture, pipeline, phased implementation |
| [Java-to-Bedrock Reference](java-to-bedrock-conversion-reference.md) | All format differences and conversion tables |
| [Geyser Integration Reference](geyser-integration-reference.md) | Geyser custom items v2 spec, deployment, limitations |
| [Validation Spec](bedrock-validation-spec.md) | All checks for validating generated output |
| [Audit Corrections](audit-findings-and-corrections.md) | Post-audit fixes: hook point, dedup, naming, UUID |

## Implementation Phases

### Phase 1: Custom Items (Critical Path)
- Scan `items/*.json` definitions (1.21.4+ format) for model references
- Scan `models/item/*.json` overrides to determine base vanilla item for each custom model
- Follow model → texture resolution chain
- Copy textures to Bedrock layout
- Generate `item_texture.json`, `manifest.json`, `pack_icon.png`
- Generate Geyser v2 mapping file (`type: "definition"` ONLY — no legacy CMD support)
- Deploy to Geyser folders

### Phase 2: Sounds, Languages, Animations
- Convert `sounds.json` → `sound_definitions.json`
- Convert `.json` lang files → `.lang` format
- Convert `.mcmeta` → `flipbook_textures.json`

### Phase 3: Blocks, Geometry, Equipment
- Custom block mappings
- 3D model → Bedrock geometry conversion
- Equipment/armor attachable generation

## Key Technical Decisions

1. **Base item detection**: Primary method is scanning vanilla `models/item/*.json` overrides
   for `custom_model_data` predicates. Fallback: new-format items, then default `minecraft:paper`.

2. **Texture resolution**: Must follow model JSON → texture reference → file path chain.
   Model paths ≠ texture paths (e.g., `gear/bronze_sword` model → `items/bronzesword` texture).

3. **Deduplication**: Merged packs may have duplicate model references in vanilla overrides.
   Deduplicate by model reference when building the reverse map.

4. **Mix.java hook**: Insert conversion call between lines 241-243 (after zip, before cleanup).
   The commented placeholder at line 258 is WRONG — unzipped folder is already deleted.

5. **UUID generation**: Deterministic from pack content hash to avoid unnecessary re-downloads.

## Testbed Validation

Real data from `TestBeds/1.21.11/plugins/ResourcePackManager/mixer/`:
- **EliteMobs**: ~50+ custom items (coins, gear, UI), 5 equipment sets, 7 sound events
- **26 vanilla model overrides** across paper, emerald, diamond_sword, diamond_axe, etc.
- **~100 unique custom items** expected in Geyser mappings after deduplication

## Sources

- [GeyserMC Custom Items](https://geysermc.org/wiki/geyser/custom-items/)
- [GeyserMC Custom Blocks](https://geysermc.org/wiki/geyser/custom-blocks/)
- [GeyserMC Resource Packs](https://geysermc.org/wiki/geyser/packs/)
- [GeyserMC Rainbow Tool](https://geysermc.org/wiki/other/rainbow/)
- [GeyserMC PackConverter](https://github.com/GeyserMC/PackConverter)
- [Bedrock manifest.json Spec](https://learn.microsoft.com/en-us/minecraft/creator/reference/content/addonsreference/packmanifest)
- [Java-to-Bedrock Texture Conversion](https://learn.microsoft.com/en-us/minecraft/creator/documents/convertingtexturepacks)
- [Bedrock Wiki - Project Setup](https://wiki.bedrock.dev/guide/project-setup)
