# Java-to-Bedrock Resource Pack Conversion Reference

> This document is the authoritative reference for converting a merged Java Edition resource pack
> into a Bedrock Edition resource pack compatible with GeyserMC. All format differences, naming
> changes, and structural transformations are catalogued here.

## Table of Contents

1. [Pack Structure](#1-pack-structure)
2. [Textures](#2-textures)
3. [Models / Geometry](#3-models--geometry)
4. [Items (Geyser Custom Items v2)](#4-items-geyser-custom-items-v2)
5. [Sounds](#5-sounds)
6. [Animations](#6-animations)
7. [Fonts](#7-fonts)
8. [Equipment / Armor Layers](#8-equipment--armor-layers)
9. [Language Files](#9-language-files)
10. [Atlases](#10-atlases)
11. [Unsupported / Non-Convertible Features](#11-unsupported--non-convertible-features)

---

## 1. Pack Structure

### Java Edition (input)
```
<pack_root>/
  pack.mcmeta              # Pack metadata (format, description, overlays)
  pack.png                 # Pack icon
  assets/
    <namespace>/           # e.g. "minecraft", "elitemobs", "freeminecraftmodels"
      textures/            # PNG textures
      models/              # JSON block/item models
      items/               # 1.21.4+ item definitions
      sounds.json          # Sound event registry
      sounds/              # OGG sound files
      font/                # Font provider definitions
      lang/                # Language files (*.json)
      atlases/             # Texture atlas definitions
      equipment/           # Equipment layer definitions
```

### Bedrock Edition (output for Geyser)
```
<bedrock_pack_root>/
  manifest.json            # Bedrock pack manifest (replaces pack.mcmeta)
  pack_icon.png            # Pack icon (replaces pack.png)
  textures/
    item_texture.json      # Item texture atlas registry (NEW - does not exist in Java)
    terrain_texture.json   # Block texture atlas registry (NEW - does not exist in Java)
    items/                 # Item textures (Java: textures/item/)
    blocks/                # Block textures (Java: textures/block/)
    entity/                # Entity textures
    models/                # Model textures
  models/                  # Not typically used for items; geometry files go in...
  attachables/             # Custom item rendering definitions (Geyser attachables)
  sounds/                  # Sound files (OGG, same format)
  sound_definitions.json   # Sound registry (replaces sounds.json)
  texts/
    en_US.lang             # Language file (different format from Java)
    languages.json         # Language registry
  textures_list.json       # Optional: explicit texture path list
  flipbook_textures.json   # Animated textures (replaces .mcmeta per-texture)
```

### Key Structural Differences

| Aspect | Java Edition | Bedrock Edition |
|--------|-------------|-----------------|
| Manifest | `pack.mcmeta` (JSON) | `manifest.json` (JSON, different schema) |
| Icon | `pack.png` | `pack_icon.png` |
| Namespace dirs | `assets/<namespace>/` | Flat structure, no namespace dirs |
| Texture dirs | `textures/block/`, `textures/item/` | `textures/blocks/`, `textures/items/` |
| Sound registry | `sounds.json` per namespace | `sound_definitions.json` at root |
| Language format | JSON key-value | `.lang` key=value (one per line) |
| Animation | `.mcmeta` sidecar files | `flipbook_textures.json` (single file) |
| Texture atlas | `atlases/*.json` (runtime stitching) | `item_texture.json` / `terrain_texture.json` |

---

## 2. Textures

### 2.1 Directory Mapping

| Java Path | Bedrock Path | Notes |
|-----------|-------------|-------|
| `assets/<ns>/textures/block/` | `textures/blocks/` | Singular → plural |
| `assets/<ns>/textures/item/` | `textures/items/` | Singular → plural |
| `assets/<ns>/textures/entity/` | `textures/entity/` | Same name |
| `assets/<ns>/textures/gui/` | `textures/gui/` | Same name |
| `assets/<ns>/textures/environment/` | `textures/environment/` | Same name |
| `assets/<ns>/textures/particle/` | `textures/particle/` | Same name |
| `assets/<ns>/textures/painting/` | `textures/painting/` | Same name |
| `assets/<ns>/textures/map/` | `textures/map/` | Same name |
| `assets/<ns>/textures/colormap/` | `textures/colormap/` | Same name |
| `assets/<ns>/textures/misc/` | `textures/misc/` | Same name |
| `assets/<ns>/textures/effect/` | *(no equivalent)* | Not supported in Bedrock |
| `assets/<ns>/textures/font/` | *(no equivalent)* | Font textures handled differently |

### 2.2 Namespace Flattening

Java organizes textures under namespace directories. Bedrock uses a flat structure. For custom
namespaces (non-minecraft), textures must be placed with namespace prefixes to avoid collisions:

- Java: `assets/elitemobs/textures/items/coin.png`
- Bedrock: `textures/items/elitemobs/coin.png`

OR use the `item_texture.json` to register the short name pointing to the full path.

### 2.3 Known Filename Differences (Vanilla Textures)

| Java Filename | Bedrock Filename |
|--------------|-----------------|
| `beehive_end.png` | `beehive_top.png` |
| `honeycomb_block.png` | `honeycomb.png` |
| `honey_block_bottom.png` | `honey_bottom.png` |
| `honey_block_side.png` | `honey_side.png` |
| `honey_block_top.png` | `honey_top.png` |
| `wither_rose.png` | `flower_wither_rose.png` |
| `block_iron.png` | `iron_block.png` |

> **Note**: For Geyser-focused conversion of CUSTOM items, vanilla texture renaming is NOT needed.
> Geyser handles vanilla texture mapping internally. We only need to handle custom namespace textures.

### 2.4 Format Considerations

| Scenario | Java Format | Bedrock Format |
|----------|------------|---------------|
| Standard textures | PNG | PNG (same) |
| Transparency | PNG with alpha | PNG or TGA with alpha channel |
| Emissive textures | PNG + emissive in shader | TGA with alpha channel mask |
| Dye-colorable | PNG + tint in model | TGA with grey alpha channel |

> **For Geyser custom items**: PNG textures are sufficient. TGA conversion is only needed
> for vanilla texture overrides with emissive/transparency, which Geyser handles separately.

### 2.5 item_texture.json (NEW - Must Be Generated)

This file maps shorthand names to texture file paths. Required for all custom items.

```json
{
  "resource_pack_name": "ResourcePackManager_Bedrock",
  "texture_name": "atlas.items",
  "texture_data": {
    "elitemobs.coins.coin1": {
      "textures": "textures/items/elitemobs/coins/coin1"
    },
    "elitemobs.gear.bronze_sword": {
      "textures": "textures/items/elitemobs/gear/bronze_sword"
    }
  }
}
```

**Naming convention for texture_data keys**:
- Colons (`:`) in identifiers become periods (`.`)
- Slashes (`/`) become underscores (`_`) OR preserved as path separators
- Must match the `icon` field in Geyser item mappings

### 2.6 terrain_texture.json (For Custom Blocks)

Similar to item_texture.json but for block textures:

```json
{
  "resource_pack_name": "ResourcePackManager_Bedrock",
  "texture_name": "atlas.terrain",
  "padding": 8,
  "num_mip_levels": 4,
  "texture_data": {
    "custom_block_texture": {
      "textures": "textures/blocks/custom_block"
    }
  }
}
```

---

## 3. Models / Geometry

### 3.1 Java Model Format

Java models use a JSON format with cuboid elements, per-face UV mapping, and display transforms:

```json
{
  "credit": "Made with Blockbench",
  "texture_size": [32, 32],
  "textures": { "0": "namespace:path/to/texture" },
  "elements": [
    {
      "from": [x, y, z],
      "to": [x, y, z],
      "rotation": { "angle": 45, "axis": "x", "origin": [x,y,z] },
      "faces": {
        "north": { "uv": [u1, v1, u2, v2], "texture": "#0" }
      }
    }
  ],
  "display": {
    "thirdperson_righthand": { "rotation": [...], "translation": [...], "scale": [...] },
    "gui": { ... }
  }
}
```

### 3.2 Bedrock Geometry Format

Bedrock uses a completely different geometry system:

```json
{
  "format_version": "1.16.0",
  "minecraft:geometry": [
    {
      "description": {
        "identifier": "geometry.custom_item",
        "texture_width": 32,
        "texture_height": 32,
        "visible_bounds_width": 2,
        "visible_bounds_height": 2,
        "visible_bounds_offset": [0, 0.5, 0]
      },
      "bones": [
        {
          "name": "root",
          "pivot": [0, 0, 0],
          "cubes": [
            {
              "origin": [-8, 0, -8],
              "size": [16, 16, 16],
              "uv": { "north": { "uv": [0, 0], "uv_size": [16, 16] } }
            }
          ]
        }
      ]
    }
  ]
}
```

### 3.3 Conversion Strategy for Custom Items

**For Geyser custom items, 3D model conversion is NOT required in most cases.**

Geyser's custom item system works by:
1. Displaying a 2D icon texture in the inventory (Bedrock side)
2. Optionally using an attachable for 3D rendering when held/worn

**Priority approach**:
- **2D items (most custom items)**: Extract the item's texture and register it in `item_texture.json`. No geometry conversion needed.
- **3D items (weapons, tools with custom models)**: Would need geometry conversion. This is complex and can be deferred to a later phase.

### 3.4 Java → Bedrock Geometry Conversion Table

| Java Concept | Bedrock Equivalent | Conversion Complexity |
|-------------|-------------------|----------------------|
| `elements[]` array | `bones[].cubes[]` | Medium - coordinate remapping |
| `from`/`to` coordinates | `origin`/`size` | Simple math: `size = to - from` |
| `rotation` per element | `rotation` per bone | Medium - bone hierarchy needed |
| Per-face UV | Per-face UV or box UV | Medium - UV format differs |
| `display` transforms | Attachable render offsets | Complex - different system |
| `parent` model inheritance | No equivalent | Must flatten/inline |
| `textures` references | Texture in attachable | Different referencing system |

> **Phase 1**: 2D icon extraction only (sufficient for Geyser item display)
> **Phase 2**: Full 3D geometry conversion (for held item rendering)

---

## 4. Items (Geyser Custom Items v2)

This is the **critical conversion path** — generating Geyser custom item mappings.

### 4.1 Input: Java Item Definitions (1.21.4+)

Located in `assets/<namespace>/items/<item_name>.json`:

```json
{
  "model": {
    "type": "minecraft:model",
    "model": "elitemobs:coins/coin1"
  }
}
```

Or with range_dispatch / select:

```json
{
  "model": {
    "type": "minecraft:select",
    "property": "minecraft:custom_model_data",
    "cases": [
      { "when": "42", "model": { "type": "minecraft:model", "model": "custom:item" } }
    ],
    "fallback": { "type": "minecraft:model", "model": "minecraft:item/diamond_sword" }
  }
}
```

### 4.2 Output: Geyser Custom Item Mapping JSON

```json
{
  "format_version": 2,
  "items": {
    "minecraft:paper": [
      {
        "type": "definition",
        "model": "elitemobs:coins/coin1",
        "bedrock_identifier": "elitemobs:coin1",
        "display_name": "Coin",
        "bedrock_options": {
          "icon": "elitemobs.coins.coin1",
          "allow_offhand": true,
          "creative_category": "items"
        }
      }
    ]
  }
}
```

### 4.3 Mapping Rules

> **RSPM only generates `type: "definition"` mappings (new system).** Legacy CMD-based
> mappings are NOT generated.

#### Java item_model → Geyser definition

| Java Field | Geyser Field | Conversion |
|-----------|-------------|-----------|
| Item definition file path | `model` | `<namespace>:<path>` from the model reference |
| Model identifier | `bedrock_identifier` | `<namespace>:<item_name>` (must NOT be `minecraft:`) |
| Display name | `display_name` | From lang file or item name |
| Texture reference in model | `bedrock_options.icon` | Converted to dot-notation for `item_texture.json` |
| Base vanilla item (from model overrides) | Root key in `items` | e.g., `"minecraft:diamond_sword"` |

### 4.4 Bedrock Identifier Rules

- Must NOT use `minecraft:` namespace
- Format: `<namespace>:<item_name>`
- Colons in display become periods in texture references
- Must be unique across all registered items

### 4.5 Icon Resolution

The `icon` field in `bedrock_options` must match a key in `item_texture.json`.

**Resolution chain**:
1. Read the Java item definition → get model reference
2. Read the Java model JSON → get texture reference (`textures.0` or `textures.layer0`)
3. Locate the texture file → copy to Bedrock textures folder
4. Register in `item_texture.json` with the icon key
5. Set `bedrock_options.icon` to that key

### 4.6 Supported Java Item Components → Geyser Components

| Java Component | Geyser Support | Notes |
|---------------|---------------|-------|
| `minecraft:max_stack_size` | Yes | Direct mapping |
| `minecraft:max_damage` | Yes | Direct mapping |
| `minecraft:food` | Yes | Nutrition + saturation |
| `minecraft:consumable` | Partial | No consume particles/sounds |
| `minecraft:equippable` | Partial | No camera overlay, no swappable |
| `minecraft:tool` | Yes | Mining speed rules |
| `minecraft:enchantable` | Yes | Maps to `slot=all` |
| `minecraft:repairable` | Yes | Repair items list |
| `minecraft:use_cooldown` | Yes | Cooldown group + seconds |
| `minecraft:enchantment_glint_override` | Yes | Boolean |
| `minecraft:attack_range` | Partial | Needs kinetic/piercing weapon |
| Custom shaders | No | Not supported in Bedrock |
| Custom model data | Yes | Via predicates |

---

## 5. Sounds

### 5.1 Java sounds.json Format

```json
{
  "entity.elitemobs.coin": {
    "sounds": [
      { "name": "elitemobs:coins/coin_pickup", "volume": 0.5 }
    ],
    "subtitle": "Coin collected"
  }
}
```

### 5.2 Bedrock sound_definitions.json Format

```json
{
  "format_version": "1.20.20",
  "sound_definitions": {
    "entity.elitemobs.coin": {
      "category": "neutral",
      "sounds": [
        {
          "name": "sounds/elitemobs/coins/coin_pickup",
          "volume": 0.5
        }
      ]
    }
  }
}
```

### 5.3 Key Differences

| Aspect | Java | Bedrock |
|--------|------|---------|
| Registry file | `sounds.json` per namespace | `sound_definitions.json` at root |
| Sound path | `namespace:path` (no extension) | `sounds/path` (no extension, relative) |
| Categories | Implied by event name | Explicit `category` field |
| Subtitles | `subtitle` field | Not supported in same way |
| File format | `.ogg` | `.ogg`, `.wav`, `.fsb` |
| Replace flag | `"replace": true` | No equivalent (always additive) |

### 5.4 Sound Categories (Bedrock)

| Category | Use |
|----------|-----|
| `ambient` | Environment sounds |
| `block` | Block interactions |
| `bottle` | Bottle sounds |
| `bucket` | Bucket sounds |
| `hostile` | Hostile mob sounds |
| `music` | Music tracks |
| `neutral` | Neutral mob sounds |
| `player` | Player sounds |
| `record` | Music discs |
| `ui` | Interface sounds |
| `weather` | Weather sounds |

> **For Geyser**: Sound conversion is lower priority. Geyser maps vanilla sounds automatically.
> Custom sounds from plugins (EliteMobs, etc.) would need conversion for Bedrock clients to hear them.

---

## 6. Animations

### 6.1 Java: Per-Texture .mcmeta Files

```json
// stone.png.mcmeta
{
  "animation": {
    "frametime": 2,
    "frames": [0, 1, 2, 3, 4, 5],
    "interpolate": false
  }
}
```

### 6.2 Bedrock: flipbook_textures.json

```json
[
  {
    "flipbook_texture": "textures/blocks/stone",
    "atlas_tile": "stone",
    "ticks_per_frame": 2,
    "frames": [0, 1, 2, 3, 4, 5],
    "replicate": 1,
    "blend_frames": false
  }
]
```

### 6.3 Conversion Table

| Java (.mcmeta) | Bedrock (flipbook_textures.json) |
|----------------|--------------------------------|
| `animation.frametime` | `ticks_per_frame` (x1, same unit) |
| `animation.frames` | `frames` (same format) |
| `animation.interpolate` | `blend_frames` |
| Filename association | `flipbook_texture` (explicit path) |
| N/A | `atlas_tile` (terrain_texture.json key) |
| N/A | `replicate` (default 1) |

---

## 7. Fonts

### 7.1 Java Font System

```json
{
  "providers": [
    {
      "type": "bitmap",
      "file": "minecraft:font/ascii.png",
      "ascent": 7,
      "chars": ["ABCDEF..."]
    },
    {
      "type": "ttf",
      "file": "minecraft:font/custom.ttf",
      "shift": [0, 0],
      "size": 11.0,
      "oversample": 2.0
    }
  ]
}
```

### 7.2 Bedrock Font System

Bedrock has very limited custom font support:
- Uses bitmap-based fonts only
- No TTF support
- Font files go in `font/` directory
- Limited to specific glyph pages

### 7.3 Conversion Status

| Feature | Convertible | Notes |
|---------|------------|-------|
| Bitmap fonts | Partial | Different format, may work with manual effort |
| TTF fonts | No | Bedrock doesn't support TTF |
| Unicode pages | Partial | Bedrock uses its own glyph system |
| Custom GUI characters | No | Common Java technique, not available in Bedrock |

> **For Geyser**: Font conversion is **out of scope** for Phase 1. Custom fonts/GUI characters
> are a Java-client-only feature. Bedrock clients will see default fonts.

---

## 8. Equipment / Armor Layers

### 8.1 Java Equipment Layer Format (1.21.2+)

```json
{
  "layers": {
    "humanoid": [
      { "texture": "elitemobs:bronze" }
    ],
    "humanoid_leggings": [
      { "texture": "elitemobs:bronze" }
    ]
  }
}
```

Texture path: `assets/<ns>/textures/entity/equipment/humanoid/<name>.png`

### 8.2 Bedrock Custom Armor

Bedrock uses the **attachable** system for custom armor rendering:

```json
{
  "format_version": "1.10.0",
  "minecraft:attachable": {
    "description": {
      "identifier": "elitemobs:bronze_chestplate",
      "materials": { "default": "armor", "enchanted": "armor_enchanted" },
      "textures": {
        "default": "textures/models/armor/bronze_1",
        "enchanted": "textures/misc/enchanted_item_glint"
      },
      "geometry": { "default": "geometry.humanoid.armor" },
      "render_controllers": ["controller.render.armor"]
    }
  }
}
```

### 8.3 Conversion Feasibility

| Feature | Convertible | Notes |
|---------|------------|-------|
| Custom armor textures | Yes | Copy texture, create attachable JSON |
| Equipment layers | Partial | Map to Bedrock armor layer system |
| Trim support | No | Bedrock trim system is different |
| Custom geometry armor | Complex | Needs geometry conversion |

> **For Geyser**: Custom armor via `equippable` component IS supported in Geyser custom items.
> The Bedrock pack needs the armor texture files; Geyser handles the mapping.

---

## 9. Language Files

### 9.1 Java Format

File: `assets/<namespace>/lang/en_us.json`
```json
{
  "item.elitemobs.coin": "Elite Coin",
  "entity.elitemobs.boss": "Elite Boss"
}
```

### 9.2 Bedrock Format

File: `texts/en_US.lang`
```
item.elitemobs.coin=Elite Coin
entity.elitemobs.boss=Elite Boss
```

Also needs `texts/languages.json`:
```json
["en_US"]
```

### 9.3 Conversion Rules

| Aspect | Java | Bedrock |
|--------|------|---------|
| Format | JSON | `.lang` (key=value) |
| File location | `assets/<ns>/lang/` | `texts/` |
| Filename case | `en_us.json` | `en_US.lang` |
| Multiple namespaces | Separate files | Single merged file |
| Comments | Not supported | `##` prefix |

---

## 10. Atlases

### 10.1 Java Atlas System

Java uses atlas definition files to control texture stitching:

```json
{
  "sources": [
    { "type": "directory", "source": "item", "prefix": "item/" },
    { "type": "directory", "source": "block", "prefix": "block/" }
  ]
}
```

### 10.2 Bedrock Equivalent

Bedrock does NOT have an atlas definition system. Instead:
- `item_texture.json` defines item texture mappings
- `terrain_texture.json` defines block texture mappings
- `textures_list.json` explicitly lists all texture paths

The Java atlas system is informational for understanding which textures exist, but does not
need direct conversion. The conversion system should USE atlas info to discover textures that
need to be registered in the Bedrock texture definition files.

---

## 11. Unsupported / Non-Convertible Features

These Java resource pack features have NO Bedrock equivalent and cannot be converted:

| Feature | Reason |
|---------|--------|
| Custom shaders (core/post) | Bedrock uses a completely different rendering pipeline |
| Custom GUI using font characters | Bedrock font system doesn't support negative-space tricks |
| Optifine/Iris CIT (Custom Item Textures) | Third-party mod, no Bedrock equivalent |
| Optifine connected textures | No Bedrock equivalent |
| Optifine custom entity models | Different system |
| Custom particle textures | Bedrock particles are data-driven differently |
| Blockstate model rotation variants | Bedrock handles block rotation differently |
| Model `parent` inheritance chains | Must be flattened before conversion |
| Predicate-based model switching | Partially via Geyser predicates, not all predicates convert |
| Overlay packs (pack_format ranges) | Bedrock has no overlay system |

### Features That Degrade Gracefully

| Feature | Bedrock Behavior |
|---------|-----------------|
| Custom 3D models | Shows 2D icon instead (acceptable for Phase 1) |
| Custom sounds | Falls back to vanilla sounds |
| Custom fonts | Falls back to default font |
| Equipment layer tints | Shows untinted texture |

---

## Appendix A: manifest.json Template

```json
{
  "format_version": 2,
  "header": {
    "name": "ResourcePackManager Bedrock",
    "description": "Auto-generated Bedrock resource pack for Geyser",
    "uuid": "<generated-uuid>",
    "version": [1, 0, 0],
    "min_engine_version": [1, 20, 0]
  },
  "modules": [
    {
      "type": "resources",
      "uuid": "<generated-uuid>",
      "version": [1, 0, 0]
    }
  ]
}
```

## Appendix B: Geyser Deployment Structure

```
plugins/Geyser-Spigot/
  packs/
    ResourcePackManager_Bedrock.zip    # Generated Bedrock resource pack
  custom_mappings/
    rspm_items.json                    # Generated Geyser custom item mappings
```

## Appendix C: Identifier Naming Conventions

| Context | Java Format | Bedrock/Geyser Format |
|---------|-----------|---------------------|
| Namespace:path | `elitemobs:coins/coin1` | `elitemobs:coins_coin1` or `elitemobs.coins.coin1` |
| Texture data key | N/A | `elitemobs.coins.coin1` (dots for colons, underscores for slashes) |
| Bedrock identifier | N/A | `elitemobs:coin1` (namespace:name, no `minecraft:`) |
| Icon reference | N/A | Must match `item_texture.json` key exactly |
