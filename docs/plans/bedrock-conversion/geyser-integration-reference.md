# Geyser Integration Reference

> Complete reference for how ResourcePackManager integrates with GeyserMC to serve
> Bedrock Edition clients. This is a Geyser-only feature — it will NOT produce
> standalone Bedrock packs.

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Geyser Custom Items v2 — Complete Spec](#2-geyser-custom-items-v2--complete-spec)
3. [Geyser Custom Blocks — Complete Spec](#3-geyser-custom-blocks--complete-spec)
4. [Geyser Custom Skulls](#4-geyser-custom-skulls)
5. [Bedrock Resource Pack for Geyser](#5-bedrock-resource-pack-for-geyser)
6. [Deployment](#6-deployment)
7. [GeyserIntegratedPack Interactions](#7-geyserintegratedpack-interactions)
8. [Limitations & Constraints](#8-limitations--constraints)

---

## 1. Architecture Overview

### How Geyser Custom Content Works

```
┌─────────────────────────────────────────────────────┐
│ Java Server (Spigot/Paper)                          │
│                                                     │
│  ResourcePackManager                                │
│    ├── Merges Java resource packs (existing)        │
│    └── NEW: Generates Bedrock conversion            │
│         ├── Bedrock .zip resource pack              │
│         │   ├── manifest.json                       │
│         │   ├── textures/item_texture.json          │
│         │   ├── textures/items/...                  │
│         │   └── sound_definitions.json (optional)   │
│         └── geyser_mappings.json                    │
│              └── Custom item definitions            │
│                                                     │
│  Geyser Plugin                                      │
│    ├── packs/                                       │
│    │   └── ResourcePackManager_Bedrock.zip  ◄──┐    │
│    └── custom_mappings/                        │    │
│        └── rspm_items.json              ◄──────┤    │
│                                                │    │
│  (RSPM copies files to Geyser dirs)  ──────────┘    │
└─────────────────────────────────────────────────────┘
         │
         ▼ Bedrock clients connect via Geyser
┌─────────────────────────────────────────────────────┐
│ Bedrock Client                                      │
│  1. Downloads Bedrock resource pack from Geyser     │
│  2. Geyser translates Java items → Bedrock items    │
│     using custom_mappings JSON                      │
│  3. Bedrock client renders custom items with        │
│     textures from the downloaded pack               │
└─────────────────────────────────────────────────────┘
```

### Prerequisites

- `enable-custom-content: true` in Geyser's `config.yml`
- Geyser API 2.9.3+ (Build #1062+) for Custom Items v2
- Server restart after adding packs/mappings

---

## 2. Geyser Custom Items v2 — Complete Spec

### 2.1 Format Structure

```json
{
  "format_version": 2,
  "items": {
    "<java_item_identifier>": [
      { /* definition */ },
      { /* definition */ }
    ]
  }
}
```

The root `items` object keys are Java item identifiers (e.g., `"minecraft:paper"`,
`"minecraft:diamond_sword"`). Each maps to an array of custom item definitions.

### 2.2 Definition Type

> **RSPM only generates `type: "definition"` mappings.** The legacy `type: "legacy"`
> format (custom_model_data-based) is NOT used.

```json
{
  "type": "definition",
  "model": "elitemobs:coins/coin1",
  "bedrock_identifier": "elitemobs:coin1",
  "display_name": "Elite Coin",
  "priority": 0,
  "bedrock_options": { },
  "components": { },
  "predicate": { },
  "predicate_strategy": "and"
}
```

| Field | Required | Type | Description |
|-------|----------|------|-------------|
| `type` | Yes | `"definition"` | Definition type identifier |
| `model` | Yes | String | Java item model identifier (e.g., `"namespace:path"`) |
| `bedrock_identifier` | Yes | String | Unique Bedrock item ID (must NOT be `minecraft:`) |
| `display_name` | No | String/JSON | Display name or text component |
| `priority` | No | Integer | Sort priority (higher = checked first) |
| `bedrock_options` | No | Object | Bedrock-specific display options |
| `components` | No | Object | Java item data components |
| `predicate` | No | Object/Array | Condition matching |
| `predicate_strategy` | No | `"and"`/`"or"` | How multiple predicates combine |

#### Group Definition

Bundles multiple definitions with shared properties:

```json
{
  "type": "group",
  "model": "elitemobs:gear/bronze_sword",
  "definitions": [
    {
      "type": "definition",
      "bedrock_identifier": "elitemobs:bronze_sword",
      "display_name": "Bronze Sword"
    },
    {
      "type": "definition",
      "model": "elitemobs:gear/bronze_sword_enchanted",
      "bedrock_identifier": "elitemobs:bronze_sword_enchanted",
      "predicate": { "condition": "enchantment_glint_override" }
    }
  ]
}
```

### 2.3 Bedrock Options

```json
{
  "bedrock_options": {
    "icon": "elitemobs.coins.coin1",
    "allow_offhand": true,
    "display_handheld": false,
    "protection_value": 0,
    "creative_category": "items",
    "creative_group": "itemGroup.name.miscellaneous",
    "tags": ["elitemobs:custom_item"]
  }
}
```

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `icon` | String | bedrock_identifier | Texture key in `item_texture.json` |
| `allow_offhand` | Boolean | `true` | Can be placed in offhand |
| `display_handheld` | Boolean | `false` | Render like tool (larger, angled) |
| `protection_value` | Integer | `0` | Armor visual protection |
| `creative_category` | String | `"none"` | `none`, `construction`, `nature`, `equipment`, `items` |
| `creative_group` | String | — | Vanilla creative group identifier |
| `tags` | String[] | — | Molang expression tags |

**Icon naming convention**: Colons become periods, slashes become underscores.
- `elitemobs:coins/coin1` → icon: `elitemobs.coins_coin1`
- Must match a key in `item_texture.json`

**Creative category is REQUIRED** if the item appears as output of a crafting recipe.

### 2.4 Java Item Components

Components follow the Minecraft wiki datapack format. Supported:

```json
{
  "components": {
    "minecraft:max_stack_size": 16,
    "minecraft:max_damage": 500,
    "minecraft:food": {
      "nutrition": 4,
      "saturation": 0.3
    },
    "minecraft:consumable": {
      "consume_seconds": 1.6
    },
    "minecraft:equippable": {
      "slot": "head",
      "model": "elitemobs:bronze_helmet"
    },
    "minecraft:tool": {
      "rules": [
        { "blocks": "#minecraft:mineable/pickaxe", "speed": 8.0 }
      ],
      "default_mining_speed": 1.0,
      "damage_per_block": 1
    },
    "minecraft:enchantable": {
      "value": 15
    },
    "minecraft:repairable": {
      "items": ["minecraft:diamond"]
    },
    "minecraft:use_cooldown": {
      "cooldown_group": "elitemobs:special",
      "seconds": 5.0
    },
    "minecraft:enchantment_glint_override": true,
    "minecraft:attack_range": 3.5,
    "minecraft:kinetic_weapon": {},
    "minecraft:piercing_weapon": {},
    "minecraft:swing_animation": {
      "duration": 0.5
    }
  }
}
```

**Remove defaults** with bang prefix: `"!minecraft:max_damage": {}`

### 2.5 Predicates

#### Condition Predicates (Boolean)

```json
{ "condition": "broken" }
{ "condition": "damaged", "expected": false }
{ "condition": "custom_model_data", "index": 0 }
{ "condition": "has_component", "component": "minecraft:food" }
{ "condition": "fishing_rod_cast" }
```

#### Match Predicates (String matching)

```json
{ "property": "charge_type", "value": "arrow" }
{ "property": "trim_material", "value": "minecraft:gold" }
{ "property": "context_dimension", "value": "minecraft:the_nether" }
{ "property": "custom_model_data", "value": "special", "index": 0 }
```

#### Range Dispatch Predicates (Numeric)

```json
{ "property": "damage", "threshold": 0.5, "scale": 1.0, "normalize": true }
{ "property": "count", "threshold": 32, "normalize": false }
{ "property": "bundle_fullness", "threshold": 0.75 }
{ "property": "custom_model_data", "threshold": 42.0, "index": 0 }
```

**Normalizable properties**: `damage`, `count` (divides by max value)
**Non-normalizable**: `bundle_fullness`, `custom_model_data`

### 2.6 Definition Sorting

Automatic sort order (when no explicit `priority`):
1. Higher `priority` first
2. Range dispatch by threshold (highest first)
3. More predicates before fewer predicates

---

## 3. Geyser Custom Blocks — Complete Spec

### 3.1 Format Structure

```json
{
  "format_version": 1,
  "blocks": {
    "<java_block_identifier>": {
      "name": "custom_block",
      "display_name": "Custom Block",
      "geometry": "geometry.custom_block",
      "material_instances": {
        "*": {
          "texture": "custom_block_texture",
          "render_method": "alpha_test",
          "face_dimming": false,
          "ambient_occlusion": false
        }
      }
    }
  }
}
```

### 3.2 Block Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `name` | String | Required | Block identifier |
| `display_name` | String | name | User-visible name |
| `geometry` | String/Object | — | Block geometry reference |
| `material_instances` | Object | — | Per-face textures |
| `collision_box` | Object/Array | — | Physics collision shape |
| `selection_box` | Object | — | Interaction outline shape |
| `destructible_by_mining` | Integer | — | Mine time (seconds) |
| `friction` | Float | 0.4 | Surface friction (0.0-1.0) |
| `light_emission` | Integer | 0 | Light level (0-15) |
| `light_dampening` | Integer | 15 | Light blocking (0-15) |
| `transformation` | Object | — | Scale/translate/rotate |
| `placement_filter` | Object | — | Placement rules |
| `place_air` | Boolean | true | Prevent double placement |
| `creative_category` | String | building_blocks | Creative tab |
| `creative_group` | String | — | Creative sub-group |
| `included_in_creative_inventory` | Boolean | true | Show in creative |
| `unit_cube` | Boolean | false | Enable tessellation |
| `tags` | String[] | — | Block tags |
| `state_overrides` | Object | — | Per-state property overrides |
| `only_override_states` | Boolean | false | Limit to specified states |

### 3.3 State Overrides

Keys are comma-separated property=value pairs:

```json
{
  "state_overrides": {
    "east=none,north=none,south=low,up=true,waterlogged=true,west=tall": {
      "geometry": "geometry.wall_variant_1",
      "destructible_by_mining": 5,
      "friction": 0.6
    }
  }
}
```

### 3.4 Transformation Constraints

- `rotation` values must be multiples of 90: `[0, 90, 180, 270]`
- `collision_box` origin range: `[-8, 0, -8]` to `[8, 24, 8]`
- `collision_box` size range: `[0, 0, 0]` to `[16, 24, 16]`
- `selection_box` origin range: `[-8, 0, -8]` to `[8, 16, 8]`

---

## 4. Geyser Custom Skulls

### 4.1 Overview

Custom skulls are player head textures registered with Geyser. They are converted to custom
blocks on the Bedrock side. Geyser generates the skull resource pack automatically.

### 4.2 Configuration (custom-skulls.yml)

Four registration methods:
1. **Player usernames**: Auto-updates on Geyser start
2. **Player UUIDs**: Auto-updates on Geyser start
3. **Player profiles**: Base64-encoded texture JSON (static)
4. **Skin hashes**: 64-char hex from texture URL (static)

### 4.3 RSPM Integration

**Custom skulls do NOT require RSPM intervention.** Geyser handles skull texture generation
internally. RSPM should NOT attempt to convert skull-related assets.

---

## 5. Bedrock Resource Pack for Geyser

### 5.1 Required Files

At minimum, the generated Bedrock pack must contain:

```
ResourcePackManager_Bedrock/
  manifest.json                    # Pack metadata
  pack_icon.png                    # Pack icon (from Java pack.png)
  textures/
    item_texture.json              # Item texture registry
    items/                         # Custom item textures
      <namespace>/
        <path>/
          <texture>.png
```

### 5.2 Optional Files

```
  textures/
    terrain_texture.json           # Block texture registry (if custom blocks)
    blocks/                        # Custom block textures
  sound_definitions.json           # Custom sound registry
  sounds/                          # Custom sound files (.ogg)
  texts/
    en_US.lang                     # Translations
    languages.json                 # Language registry
  attachables/                     # Custom armor/held item rendering
  models/                          # Custom geometry (if converting 3D models)
```

### 5.3 manifest.json Generation

```json
{
  "format_version": 2,
  "header": {
    "name": "ResourcePackManager Bedrock",
    "description": "Auto-generated by ResourcePackManager for Geyser",
    "uuid": "<deterministic-uuid-from-pack-content>",
    "version": [1, 0, 0],
    "min_engine_version": [1, 21, 0]
  },
  "modules": [
    {
      "type": "resources",
      "uuid": "<deterministic-uuid-different-from-header>",
      "version": [1, 0, 0]
    }
  ],
  "metadata": {
    "authors": ["ResourcePackManager"],
    "generated_with": {
      "ResourcePackManager": ["1.8.0"]
    }
  }
}
```

**UUID generation**: Use deterministic UUIDs based on pack content hash so the UUID
only changes when content actually changes. This prevents unnecessary re-downloads.

**Version bumping**: Increment version when content changes to trigger client re-download.

### 5.4 item_texture.json Generation

```json
{
  "resource_pack_name": "ResourcePackManager_Bedrock",
  "texture_name": "atlas.items",
  "texture_data": {
    "<icon_key>": {
      "textures": "textures/items/<path_to_texture>"
    }
  }
}
```

**Icon key format**: `namespace.path.to.texture` (dots for separators)
**Texture path**: Relative from pack root, no file extension

---

## 6. Deployment

### 6.1 Auto-Deploy to Geyser

After generating the Bedrock pack and mappings, RSPM should:

1. Detect Geyser installation (check for `plugins/Geyser-Spigot/` or equivalent)
2. Copy `ResourcePackManager_Bedrock.zip` to `<geyser_dir>/packs/`
3. Copy `rspm_items.json` to `<geyser_dir>/custom_mappings/`
4. Log a message indicating Geyser restart is needed

### 6.2 Geyser Platform Detection

| Platform | Data Folder |
|----------|------------|
| Spigot/Paper | `plugins/Geyser-Spigot/` |
| Fabric | `config/Geyser-Fabric/` |
| NeoForge | `config/Geyser-NeoForge/` |
| Velocity | `plugins/Geyser-Velocity/` |
| BungeeCord | `plugins/Geyser-BungeeCord/` |
| Standalone | Root folder |

### 6.3 File Placement

```
<geyser_data_folder>/
  packs/
    ResourcePackManager_Bedrock.zip    # Generated
  custom_mappings/
    rspm_items.json                    # Generated
```

### 6.4 Geyser Config Requirements

The user must have `enable-custom-content: true` in Geyser's config. RSPM should:
- Check this setting if possible
- Log a warning if it appears disabled
- Document the requirement clearly

---

## 7. GeyserIntegratedPack Interactions

GeyserIntegratedPack is Geyser's built-in resource pack that fixes Bedrock/Java parity issues.
It has **lower priority** than user packs, so RSPM's generated pack will take precedence.

### Key considerations:
- Entity definitions in GeyserIntegratedPack may conflict with custom entity textures
- If RSPM's pack includes entity definitions, they need manual merging guidance
- For items and blocks, there is NO conflict — GeyserIntegratedPack doesn't handle custom items

---

## 8. Limitations & Constraints

### 8.1 Bedrock Client Limitations

| Limitation | Impact | Workaround |
|-----------|--------|-----------|
| No custom shaders | Visual effects lost | None — document as unsupported |
| No TTF fonts | Custom fonts lost | Falls back to default |
| No GUI character tricks | Custom HUDs/menus lost | Use Geyser Forms API |
| No dynamic component changes | Can't modify items at runtime | Use predicates for variants |
| No custom creative groups | Must use vanilla groups | Document available groups |
| Wearable + stackable conflict | Items can't be both | Remove one component |
| 32-bit position precision | Visual glitches far from spawn | Geyser extension fix |
| No mod item translation | Modded items show as unknown | Geyser NonVanilla API (manual) |

### 8.2 Geyser Custom Items Limitations

| Limitation | Description |
|-----------|-------------|
| No runtime modification | All variants need separate definitions with predicates |
| Non-vanilla enchantments | Won't display on Bedrock |
| Custom armor overlay | Not supported in equippable component |
| Swappable equipment | Not supported |
| Consume particles/sounds | Not supported in consumable |
| Some predicates | `fishing_rod_cast`, `charge_type` have limited Bedrock support |

### 8.3 Conversion Scope Boundaries

**Will convert (Phase 1)**:
- Custom item textures → Bedrock item textures + item_texture.json
- Java item definitions → Geyser custom item mappings
- Item display names → Bedrock display names
- Basic item components (stack size, durability, food, etc.)

**Will convert (Phase 2)**:
- Custom sounds → Bedrock sound_definitions.json
- Language files → Bedrock .lang format
- Animated textures → flipbook_textures.json

**Will convert (Phase 3)**:
- Custom block textures → terrain_texture.json
- 3D model geometry conversion (complex)
- Equipment/armor attachables

**Will NOT convert (ever)**:
- Custom shaders
- Custom fonts / GUI characters
- Optifine/Iris features
- Particle textures
- Overlay packs (Bedrock has no overlay system)

### 8.4 Conflict with Rainbow / PackConverter

If users are already using Rainbow or PackConverter alongside Geyser:
- RSPM should detect existing files in Geyser's folders
- Option to skip conversion if user-managed files exist
- Option to merge / override with warning
- Document mutual exclusivity

---

## Appendix: Geyser Config Reference

Key settings in Geyser's `config.yml` that affect custom content:

```yaml
# Enable custom items, blocks, and skulls
gameplay:
  enable-custom-content: true

# Resource pack settings
resource-pack:
  # Automatically send resource packs to Bedrock clients
  enabled: true
  # Force Bedrock clients to accept the resource pack
  forced: false
```
