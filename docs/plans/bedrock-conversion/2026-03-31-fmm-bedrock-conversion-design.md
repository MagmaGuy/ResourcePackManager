# FMM Bedrock Conversion Design — 3D Models via Armor Stands

> **Date**: 2026-03-31
> **Status**: Approved
> **Scope**: Convert FreeMinecraftModels bone models to Bedrock 3D geometry for Geyser
> **Replaces**: 2026-03-30-bedrock-conversion-design.md (generic conversion approach — abandoned)

## 1. Goal

After ResourcePackManager merges Java resource packs (including FMM's output), automatically
convert FMM's 3D bone models into a Bedrock resource pack with correct geometry, textures,
attachables, and Geyser v2 custom item mappings — so Bedrock clients connecting through
Geyser see FMM's custom entity models rendered as 3D on armor stand heads.

**Non-goals (dropped):**
- Generic item icon conversion
- Sound/language/animation conversion
- Equipment/armor conversion
- Block texture conversion
- Legacy CustomModelData support (1.21.4+ only)

## 2. How FMM Renders on Bedrock

FMM detects Bedrock players (via Geyser/Floodgate API) and uses armor stand fallback:

1. Each bone = one invisible marker armor stand
2. Each armor stand gets `LEATHER_HORSE_ARMOR` on head slot
3. On 1.21.4+: `itemMeta.setItemModel(boneName)` — e.g., `freeminecraftmodels:01_em_wolf/bodyback`
4. FMM pre-scales cube coordinates by `ARMOR_STAND_HEAD_SIZE_MULTIPLIER = 0.4`
5. Java display transform: `head: { translation: [0, -6.4, 0], scale: [4, 4, 4] }`
6. Net render scale: `0.4 * 4 = 1.6x` original Blockbench size

For Bedrock to render these, we need:
- Bedrock geometry (.geo.json) with the 3D cubes
- An attachable that binds the geometry to the head slot
- Geyser v2 mappings connecting `minecraft:leather_horse_armor` + item_model to the Bedrock item

## 3. Pipeline

```
Merged Java Pack (post-mix, pre-cleanup)
  |
  v
FmmModelScanner
  Scan assets/freeminecraftmodels/models/{modelName}/*.json
  Skip display/ subdirectory
  Group bones by model name
  |
  v
TextureStitcher (once per model)
  Collect unique textures referenced by all bones in model
  If single texture: copy as-is, no UV remap needed
  If multiple: stitch horizontally into atlas PNG
  Record sprite map: textureIndex -> {x, y, width, height}
  Write atlas to textures/entity/{modelName}/atlas.png in Bedrock pack
  |
  v
FmmGeometryConverter (once per bone)
  Read bone's Java model JSON (elements, textures, display)
  Convert coordinates: subtract centre (8,0,8), invert X, flip up/down UV
  Remap UVs using sprite map (scale by sprite size, offset by sprite position)
  Write .geo.json with bone binding: q.item_slot_to_bone_name(context.item_slot)
  texture_width/texture_height = atlas dimensions
  |
  v
FmmAttachableGenerator (once per bone)
  Generate attachable JSON referencing geometry + atlas texture
  Head-slot animation with tunable position/rotation/scale
  Initial transforms derived from FMM's display.head values
  |
  v
FmmGeyserMappingBuilder (once, all bones)
  Generate single Geyser v2 mappings file
  All items under minecraft:leather_horse_armor
  model field = exact boneName from FMM's setItemModel()
  |
  v
BedrockConversion orchestrator
  Write manifest.json
  Zip Bedrock pack
  Deploy to Geyser packs/ and custom_mappings/
```

## 4. Geometry Conversion

Per bone, convert Java model JSON to Bedrock .geo.json.

### 4.1 Coordinate Conversion (matches Rainbow)

- Subtract centre offset `(8, 0, 8)` from all `from`/`to` coordinates
- `origin = min(from, to)`, `size = max(from, to) - origin`
- Invert X axis: `origin.x = -(origin.x + size.x)`
- Zero-thickness cubes get 0.01 minimum thickness
- Rotation: negate X and Z angles, keep Y
- Rotation pivot: subtract centre offset, invert X

### 4.2 UV Remapping

Java UVs are in `[0, 16]` range. Each face references a texture index (`#0`, `#1`).

For stitched atlases:
1. Look up texture index in sprite map -> get `{x, y, width, height}` in atlas
2. Scale UV: `u * (spriteWidth / 16)`, `v * (spriteHeight / 16)`
3. Offset: `u += spriteX`, `v += spriteY`
4. Up/down faces: flip (origin at max, negative size)

For single-texture models: scale by `(textureWidth / 16, textureHeight / 16)`, no offset.

### 4.3 Bone Structure

- Single bone named `"bone"`
- `binding: "q.item_slot_to_bone_name(context.item_slot)"`
- Pivot = centre of all cubes' bounding box (X inverted)
- `texture_width` / `texture_height` = atlas dimensions (or source texture dimensions)

## 5. Texture Stitching

One atlas per model. All bones in a model share the same texture set.

### Algorithm

1. Collect unique textures across all bones of a model
2. Load each PNG with `BufferedImage`
3. Horizontal layout: texture 0 at x=0, texture 1 at x=width0, etc.
4. Atlas height = max height of all textures
5. Write stitched PNG to Bedrock pack

### Sprite Map

```
textureIndex -> { x: offsetInAtlas, y: 0, width: textureWidth, height: textureHeight }
```

### Edge Cases

- **Single texture**: skip stitching, copy PNG directly, no UV remapping offset needed
- **Animated textures (.mcmeta)**: extract first frame only before stitching
- **Different texture heights**: pad shorter textures (transparent) to atlas height

## 6. Attachables

One attachable per bone. Binds geometry to head slot with animation transforms.

### Structure

```json
{
  "format_version": "1.10.0",
  "minecraft:attachable": {
    "description": {
      "identifier": "<bedrock_identifier>",
      "materials": {
        "default": "entity",
        "enchanted": "entity_alphatest_glint"
      },
      "textures": {
        "default": "<atlas_texture_path>",
        "enchanted": "textures/misc/enchanted_item_glint"
      },
      "geometry": {
        "default": "<geometry_id>"
      },
      "scripts": {
        "pre_animation": ["v.head = c.item_slot == 'head';"],
        "animate": [{ "head": "v.head" }]
      },
      "animations": {
        "head": "<animation_id>"
      },
      "render_controllers": ["controller.render.item_default"]
    }
  }
}
```

### Head Animation Transforms

These are initial values derived from FMM's `display.head` (`translation: [0, -6.4, 0]`,
`scale: [4, 4, 4]`) and will need empirical tuning against a real Geyser testbed.

All transform values are centralised in `FmmAttachableGenerator` for easy adjustment.

**Note**: Rainbow's formula (`translation / 0.0625 * scale + offset`) does NOT apply directly
to FMM models because FMM writes raw display values, not values processed through Java's
model loading pipeline. The `/0.0625` factor undoes Java's internal scaling which FMM bypasses.

## 7. Geyser v2 Mappings

Single JSON file mapping all FMM bones under `minecraft:leather_horse_armor`:

```json
{
  "format_version": 2,
  "items": {
    "minecraft:leather_horse_armor": [
      {
        "type": "definition",
        "model": "freeminecraftmodels:01_em_wolf/bodyback",
        "bedrock_identifier": "freeminecraftmodels:01_em_wolf_bodyback",
        "display_name": "bodyback",
        "bedrock_options": {
          "icon": "fmm.01_em_wolf.bodyback",
          "allow_offhand": true
        }
      }
    ]
  }
}
```

The `model` field must exactly match what FMM sets via `setItemModel(boneName)`.

## 8. File Structure

### New/Modified Classes

```
bedrock/
  BedrockConversion.java          # Orchestrator — rebuilt for FMM pipeline
  GeyserDeployer.java             # Keep as-is
  converter/
    FmmModelScanner.java          # NEW: scans FMM bone models, groups by model
    TextureStitcher.java          # NEW: stitches model textures into atlas
    FmmGeometryConverter.java     # NEW: Java bone model -> Bedrock .geo.json
    FmmAttachableGenerator.java   # NEW: generates attachable + head animation
    FmmGeyserMappingBuilder.java  # NEW: Geyser v2 mappings for leather_horse_armor
  model/
    BedrockManifest.java          # Keep as-is
    FmmBoneModel.java             # NEW: data class for discovered bone
    SpriteInfo.java               # NEW: atlas sprite position/size
  util/
    BedrockNaming.java            # Keep as-is
    BedrockZip.java               # Keep as-is
```

### Deleted Classes

- `ItemConverter.java` — generic item scanning
- `TextureConverter.java` — replaced by TextureStitcher
- `ModelResolver.java` — FMM models are self-contained
- `ItemTextureAtlasBuilder.java` — not needed for 3D geometry
- `GeyserMappingBuilder.java` — replaced by FMM-specific version
- `GeometryConverter.java` — replaced by FMM-specific version
- `SoundConverter.java` — dropped
- `LanguageConverter.java` — dropped
- `AnimationConverter.java` — dropped
- `BlockConverter.java` — dropped
- `EquipmentConverter.java` — dropped
- `DiscoveredItem.java` — replaced
- `GeyserItemDefinition.java` — replaced
- `ItemTextureEntry.java` — not needed

## 9. Configuration

Existing config options kept:
```yaml
bedrockConversion:
  enabled: false
  autoDeployToGeyser: true
  geyserFolder: ""
```

No new config options needed. Transform tuning is done in code (FmmAttachableGenerator)
until values are validated, then could be exposed if needed.

## 10. Testing Strategy

1. **Unit**: verify geometry coordinate conversion against known input/output pairs
2. **Unit**: verify texture stitching produces correct atlas dimensions and sprite map
3. **Unit**: verify UV remapping math with multi-texture models
4. **Integration**: run against testbed with EliteMobs FMM models, inspect generated files
5. **Live**: deploy to Geyser testbed, connect with Bedrock client, verify 3D rendering
6. **Iterate**: tune head animation transforms based on visual results
