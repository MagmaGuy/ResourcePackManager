# Bedrock Conversion Design — GeyserMC Integration

> **Date**: 2026-03-30
> **Status**: Draft — awaiting review
> **Scope**: Geyser-only. This feature will ONLY work through GeyserMC.

## 1. Goal

After ResourcePackManager merges Java resource packs, automatically generate:
1. A **Bedrock Edition resource pack** (`.zip`) for Geyser's `packs/` folder
2. A **Geyser custom item mappings JSON** for Geyser's `custom_mappings/` folder
3. Optionally, **custom block mappings** and **sound definitions**

Deploy both files to the detected Geyser installation so Bedrock clients connecting
through Geyser see custom items with correct textures and behavior.

## 2. Architecture

### 2.1 Pipeline Position

The Bedrock conversion runs **after** the Java resource pack merge is complete and
the final `.zip` is produced, but **before** cleanup of the unzipped working directory.

```
Mix.mixResourcePacks()
  ├── ... existing merge pipeline ...
  ├── zip the merged Java pack
  ├── ★ NEW: generateBedrockPack()        ◄── runs on the unzipped merged output
  │     ├── scanJavaItems()               # Walk assets/*/items/ for definitions
  │     ├── resolveModels()               # Follow model references to get textures
  │     ├── copyTextures()                # Copy textures to Bedrock layout
  │     ├── generateItemTexture()         # Build item_texture.json
  │     ├── generateGeyserMappings()      # Build geyser_mappings.json
  │     ├── generateManifest()            # Build manifest.json
  │     ├── generateSoundDefinitions()    # Build sound_definitions.json (Phase 2)
  │     ├── generateLanguageFiles()       # Build texts/*.lang (Phase 2)
  │     ├── zipBedrockPack()              # Create the .zip
  │     └── deployToGeyser()              # Copy to Geyser folders
  ├── cleanup unzipped working directory
  └── SHA1 + autohost (existing)
```

### 2.2 Package Structure

New package: `com.magmaguy.resourcepackmanager.bedrock`

```
bedrock/
  BedrockConversion.java           # Orchestrator (entry point from Mix.java)
  BedrockPackBuilder.java          # Assembles the Bedrock resource pack folder
  GeyserMappingBuilder.java        # Generates Geyser custom item/block mappings
  GeyserDeployer.java              # Detects Geyser and deploys files
  converter/
    ItemConverter.java             # Java item defs → Geyser item mappings
    TextureConverter.java          # Copies/remaps textures
    ModelResolver.java             # Resolves Java model → texture chain
    SoundConverter.java            # sounds.json → sound_definitions.json
    LanguageConverter.java         # JSON lang → .lang format
    AnimationConverter.java        # .mcmeta → flipbook_textures.json
  model/
    BedrockManifest.java           # manifest.json POJO
    ItemTextureAtlas.java          # item_texture.json POJO
    GeyserItemMapping.java         # Geyser mapping file POJO
    GeyserItemDefinition.java      # Single item definition
    BedrockSoundDefinitions.java   # sound_definitions.json POJO
  util/
    BedrockNaming.java             # Identifier conversion utilities
    BedrockZip.java                # Zip utility for Bedrock pack
```

### 2.3 Configuration

New config options in `config.yml`:

```yaml
# Bedrock/Geyser integration
bedrockConversion:
  enabled: false                   # Disabled by default until stable
  autoDeployToGeyser: true         # Auto-copy to Geyser folders
  geyserFolder: ""                 # Auto-detect if empty; manual override path
  convertSounds: false             # Phase 2
  convertLanguages: false          # Phase 2
```

---

## 3. Conversion Pipeline — Detailed Design

### 3.1 Phase 1: Custom Items (Critical Path)

This is the highest-priority feature and must work correctly before anything else.

#### Step 1: Scan Java Item Definitions

Walk the merged resource pack's `assets/*/items/` directories to find all item definition
JSON files. These are the 1.21.4+ format item definitions.

**Input**: `<merged_pack>/assets/<namespace>/items/<item_name>.json`

**Example input**:
```json
{
  "model": {
    "type": "minecraft:model",
    "model": "elitemobs:coins/coin1"
  }
}
```

**What we extract**:
- Namespace (from directory path)
- Item name (from filename)
- Model reference (from `model.model` field)
- Model type (simple, select, range_dispatch, composite, condition, etc.)

**Edge cases to handle**:
- `minecraft:select` with `item_model` cases
- `minecraft:range_dispatch` with threshold-based variants
- `minecraft:condition` with boolean switching
- `minecraft:composite` combining multiple models
- Nested model references (model → model → model)
- Missing model files (log warning, skip)
- Vanilla minecraft namespace items (skip — Geyser handles these)

#### Step 2: Resolve Model → Texture Chain

For each item definition's model reference, read the Java model JSON to find the texture.

**Resolution chain**:
```
Item definition → model reference → Model JSON → textures field → texture path
```

**Java model textures field variants**:
```json
// Simple texture reference
{ "textures": { "0": "elitemobs:coins/coin1" } }

// Layer-based (vanilla items)
{ "textures": { "layer0": "minecraft:item/diamond_sword" } }

// With particle texture
{ "textures": { "0": "elitemobs:coins/coin1", "particle": "elitemobs:coins/coin1" } }
```

**Resolution rules**:
1. Check for `textures.layer0` first (standard item texture)
2. Fall back to `textures.0` (numbered textures)
3. Fall back to first texture in the map
4. If model has `parent`, recursively resolve parent's texture
5. If no texture found, use a fallback missing texture

**The texture key gives us**: `<namespace>:<path>` → resolve to file
`assets/<namespace>/textures/<path>.png`

#### Step 3: Copy Textures to Bedrock Layout

Copy each resolved texture to the Bedrock pack structure:

**Java source**: `assets/<namespace>/textures/<path>.png`
**Bedrock destination**: `textures/items/<namespace>/<path>.png`

For the coin example:
- From: `assets/elitemobs/textures/coins/coin1.png`
- To: `textures/items/elitemobs/coins/coin1.png`

#### Step 4: Generate item_texture.json

Build the texture atlas registry mapping icon keys to texture paths:

```json
{
  "resource_pack_name": "ResourcePackManager_Bedrock",
  "texture_name": "atlas.items",
  "texture_data": {
    "elitemobs.coins.coin1": {
      "textures": "textures/items/elitemobs/coins/coin1"
    }
  }
}
```

**Key generation**: `<namespace>.<path_with_dots>` where slashes become dots.

#### Step 5: Generate Geyser Custom Item Mappings

For each discovered custom item, generate a Geyser v2 definition:

```json
{
  "format_version": 2,
  "items": {
    "minecraft:paper": [
      {
        "type": "definition",
        "model": "elitemobs:coins/coin1",
        "bedrock_identifier": "elitemobs:coin1",
        "display_name": "coin1",
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

**Critical challenge**: Determining which Java base item each custom item belongs to.

The Java item definitions are stored under `assets/<namespace>/items/`. But Geyser needs
to know the BASE Java item (e.g., `minecraft:paper`, `minecraft:diamond_sword`).

**How to determine the base item**:

For custom namespace items (e.g., `assets/elitemobs/items/coins/coin1.json`):
- These are custom items registered by plugins like EliteMobs
- The plugin registers them on a base vanilla item (usually `minecraft:paper`)
- **We need metadata from the plugin** to know the base item

**Approaches** (in order of reliability):

1. **Scan vanilla model overrides** (`models/item/*.json`): Walk all
   `assets/minecraft/models/item/*.json` looking for `overrides` arrays that reference
   custom namespace models. These give us the `base_item → model_reference` mapping.
2. **Default assumption**: If undeterminable, assume `minecraft:paper` (most plugins use this)
3. **Configuration override**: Allow manual mapping in RSPM config

### VALIDATED: Real Testbed Data (1.21.11)

The merged resource pack contains vanilla model overrides that map base items to custom models:

| Vanilla Model File | Override Count | Custom Items |
|-------------------|---------------|-------------|
| `paper.json` | 100 | UI elements, misc items |
| `emerald.json` | 120 | Coins (coin2, etc.) |
| `diamond_sword.json` | ~100 | Swords + tridents (all tiers) |
| `diamond_axe.json` | 50 | Axes (all tiers) |
| `diamond_hoe.json` | 50 | Scythes (all tiers) |
| `iron_horse_armor.json` | 50 | Helmets (all tiers) |
| `netherite_sword.json` | 30 | Special swords (primis_gladius) |
| `nether_star.json` | 10 | coin4 |
| `barrier.json` | ~10 | UI (redcross) |
| + ~15 more files | varying | GUI panes, banners, etc. |

**The testbed also has 1.21.4+ item definitions** in `assets/elitemobs/items/` (custom
namespace). These are the primary source of truth for item → model mappings. The vanilla
model overrides provide the base item mapping.

### Item Discovery Algorithm (New System Only)

We ONLY generate Geyser `type: "definition"` mappings (not legacy). The algorithm:

```
1. Discover custom items (PRIMARY SOURCE):
   For each assets/<non-minecraft-namespace>/items/<path>.json:
     Read the item definition → get model reference
     Store: { namespace, itemPath, modelRef }

2. Build base-item reverse map from vanilla model overrides:
   For each assets/minecraft/models/item/<vanilla_item>.json:
     For each override in overrides[]:
       reverseMap[override.model] = "minecraft:<vanilla_item>"

3. Match custom items to base items:
   For each discovered custom item:
     baseItem = reverseMap[item.modelRef] ?? "minecraft:paper"

4. Generate Geyser definition:
   {
     "type": "definition",
     "model": "<namespace>:<model_path>",
     "bedrock_identifier": "<namespace>:<item_name>",
     ...
   }
```

**Key**: We scan `items/*.json` for discovery, and `models/item/*.json` ONLY to determine
which vanilla base item each custom model belongs to. We do NOT generate legacy CMD mappings.

#### Step 6: Generate manifest.json

```json
{
  "format_version": 2,
  "header": {
    "name": "ResourcePackManager Bedrock",
    "description": "Auto-generated by ResourcePackManager for GeyserMC",
    "uuid": "<deterministic>",
    "version": [1, 0, 0],
    "min_engine_version": [1, 21, 0]
  },
  "modules": [
    {
      "type": "resources",
      "uuid": "<deterministic>",
      "version": [1, 0, 0]
    }
  ],
  "metadata": {
    "authors": ["ResourcePackManager"],
    "generated_with": {
      "ResourcePackManager": ["<plugin_version>"]
    }
  }
}
```

**UUID generation**: Use UUID v5 (name-based) from a fixed namespace UUID + pack content hash.
This ensures:
- Same content → same UUID → no unnecessary re-download
- Changed content → changed UUID → triggers re-download

**Version**: Increment on each generation. Store last version in `data.yml`.

#### Step 7: Zip and Deploy

1. Zip the Bedrock pack folder → `ResourcePackManager_Bedrock.zip`
2. Detect Geyser installation:
   - Check `plugins/Geyser-Spigot/` (most common)
   - Check `plugins/Geyser-*/` for other platforms
   - Check `config/Geyser-*/` for Fabric/NeoForge
3. Copy zip to `<geyser>/packs/`
4. Copy mappings to `<geyser>/custom_mappings/`
5. Log: "Bedrock resource pack deployed to Geyser. Restart Geyser to apply."

### 3.2 Phase 2: Sounds, Languages, Animations

#### Sound Conversion

Walk `assets/*/sounds.json` files and convert to Bedrock `sound_definitions.json`:

**Java format** → **Bedrock format** mapping:
```
sounds.json {                          sound_definitions.json {
  "event.name": {                        "format_version": "1.20.20",
    "sounds": [                          "sound_definitions": {
      { "name": "ns:path" }               "event.name": {
    ],                                       "category": "<inferred>",
    "subtitle": "..."                        "sounds": [
  }                                            { "name": "sounds/ns/path" }
}                                            ]
                                           }
                                         }
                                       }
```

**Sound file copying**: `assets/<ns>/sounds/<path>.ogg` → `sounds/<ns>/<path>.ogg`

**Category inference**: Map event name prefixes to Bedrock categories:
- `entity.*` → `neutral` or `hostile` (based on mob type)
- `block.*` → `block`
- `music.*` → `music`
- `ui.*` → `ui`
- Default: `neutral`

#### Language Conversion

Convert JSON key-value to `.lang` format:

**Java**: `assets/<ns>/lang/en_us.json` → `{ "key": "value" }`
**Bedrock**: `texts/en_US.lang` → `key=value`

Merge all namespace language files into one `en_US.lang`.

#### Animation Conversion

Find `.mcmeta` sidecar files and convert to `flipbook_textures.json`:

**Java**: `textures/blocks/stone.png.mcmeta`
**Bedrock**: Entry in `flipbook_textures.json`

```json
{
  "flipbook_texture": "textures/blocks/stone",
  "atlas_tile": "stone",
  "ticks_per_frame": <frametime>,
  "frames": <frames_array>,
  "blend_frames": <interpolate>
}
```

### 3.3 Phase 3: Blocks, Geometry, Equipment

These are complex and lower priority. Design TBD after Phase 1 is stable.

**Custom blocks**: Require understanding of which Java blocks map to custom blocks.
Similar reverse-mapping problem as items.

**3D geometry**: Converting Java JSON models to Bedrock geometry format. Complex
coordinate and UV remapping. May not be necessary if 2D icons are acceptable.

**Equipment/armor**: Creating Bedrock attachable definitions for custom armor textures.

---

## 4. Testing Strategy

### 4.1 Unit Tests

Test each converter in isolation with known input/output pairs.

| Test | Input | Expected Output |
|------|-------|----------------|
| `ItemConverter` | EliteMobs coin item def | Geyser mapping with correct base item |
| `TextureConverter` | Java texture path | Correct Bedrock path + copied file |
| `ModelResolver` | Model with parent chain | Resolved final texture |
| `BedrockNaming` | `elitemobs:coins/coin1` | `elitemobs.coins.coin1` |
| `SoundConverter` | Java sounds.json | Bedrock sound_definitions.json |
| `LanguageConverter` | Java en_us.json | Bedrock en_US.lang |

### 4.2 Integration Tests Using TestBeds

Use the existing `TestBeds/harness/Invoke-SmokeMatrix.ps1` infrastructure:

1. **Build RSPM** with bedrock conversion enabled
2. **Run smoke test** on 1.21.11 testbed
3. **Verify output exists**:
   - `output/ResourcePackManager_Bedrock.zip` exists
   - `output/rspm_geyser_mappings.json` exists
4. **Validate Bedrock pack structure**:
   - Unzip and verify `manifest.json` is valid
   - Verify `item_texture.json` has entries for all custom items
   - Verify all referenced texture files exist
5. **Validate Geyser mappings**:
   - JSON is valid
   - All `bedrock_identifier` values are unique
   - All `icon` values match keys in `item_texture.json`
   - All `model` references match Java item definitions
   - No `minecraft:` namespace in bedrock_identifiers

### 4.3 Expected Output Validation

For the current testbed content (EliteMobs + FreeMinecraftModels), we expect:

**EliteMobs items that should convert**:
- `coins/coin1` through `coins/coin4`
- `elitescroll/elitescroll`
- All `gear/*` items (bronze, corrupted, living, palladium, ultimatium × weapon types)
- Special items (magmaguys_toothpick, frost_palace swords, primis_gladius)

**Expected item_texture.json entries**: One per unique custom item texture
**Expected geyser_mappings.json entries**: One definition per custom item

### 4.4 Bedrock Format Validation Script

Create a validation script that can be run as part of the smoke harness:

```
TestBeds/harness/Validate-BedrockPack.ps1
```

Checks:
- [ ] manifest.json exists and has valid UUIDs
- [ ] manifest.json format_version is 2
- [ ] manifest.json has "resources" module type
- [ ] item_texture.json exists and is valid JSON
- [ ] Every texture referenced in item_texture.json exists as a file
- [ ] Every icon in geyser_mappings.json exists in item_texture.json
- [ ] No duplicate bedrock_identifiers
- [ ] No minecraft: namespace in bedrock_identifiers
- [ ] All referenced Java models exist in the merged pack
- [ ] Pack is valid ZIP
- [ ] No empty texture_data entries

### 4.5 Manual Verification Checklist

For actual Geyser testing (requires Bedrock client):

1. Deploy pack + mappings to Geyser test server
2. Connect with Bedrock client
3. Verify pack downloads successfully
4. Give self custom items via commands
5. Verify items show correct icon in inventory
6. Verify items show correct name
7. Verify items can be held, used, etc.
8. Check console for Geyser errors about malformed mappings

---

## 5. Edge Cases & Risk Register

### 5.1 Known Edge Cases

| # | Edge Case | Impact | Mitigation |
|---|----------|--------|-----------|
| 1 | Custom item on unknown base item | Can't create Geyser mapping | Scan vanilla items for references; fall back to paper |
| 2 | 3D model item (no flat texture) | No 2D icon available | Render model to 2D icon or use missing texture |
| 3 | Animated item texture (.mcmeta) | Static on Bedrock | Copy first frame only; note in docs |
| 4 | Multiple items sharing same model | Duplicate bedrock_identifiers | Append numeric suffix to make unique |
| 5 | Model with deeply nested parent chain | Stack overflow in resolution | Max depth limit (16), warn if exceeded |
| 6 | Texture in overlay directory | May not be in base pack | Check overlays too during texture resolution |
| 7 | Custom namespace = "minecraft" | Geyser rejects minecraft: bedrock IDs | Remap to `rspm_minecraft:` prefix |
| 8 | Very long identifier names | Bedrock may truncate | Truncate with hash suffix if >64 chars |
| 9 | Non-PNG textures (TGA, etc.) | Unexpected format | Only copy PNG; log warning for others |
| 10 | Geyser not installed | No deployment target | Skip deployment, still generate files |
| 11 | Existing files in Geyser folders | Overwriting user content | Only overwrite RSPM-generated files (check metadata) |
| 12 | Multiple Geyser platforms | Which folder to use | Detect all, deploy to first found, log |
| 13 | Pack too large for Bedrock | Client fails to download | Compress textures, warn if >50MB |
| 14 | Unicode in item names | Bedrock encoding issues | Ensure UTF-8, test with special chars |
| 15 | Empty resource pack (no custom items) | Unnecessary empty pack | Skip bedrock conversion if no custom items found |
| 16 | select/range_dispatch with many cases | Many Geyser definitions needed | Generate one definition per case |
| 17 | Composite models | Multiple textures per item | Use primary/first texture for icon |
| 18 | Condition models | Two variants per condition | Generate definitions with predicates |

### 5.2 Risk Register

| Risk | Probability | Impact | Mitigation |
|------|------------|--------|-----------|
| Geyser API format changes | Medium | High | Pin to v2, monitor Geyser releases |
| Bedrock pack format changes | Low | Medium | Use stable format_version 2 |
| New Java item definition types | Medium | Medium | Extensible converter pattern |
| Plugin uses non-standard item registration | Medium | High | Configurable base item override |
| Performance impact on merge pipeline | Low | Medium | Run conversion async |
| Testbed doesn't cover all item types | High | Medium | Add synthetic test items |

---

## 6. Implementation Order

### Phase 1A: Core Infrastructure (Foundation)
1. Create `bedrock` package structure
2. Implement `BedrockNaming` utility (identifier conversion)
3. Implement `BedrockManifest` model + generation
4. Implement `BedrockPackBuilder` (folder creation, zipping)
5. Wire into `Mix.java` (commented call → actual call)
6. Configuration in `DefaultConfig`

### Phase 1B: Item Conversion (Critical Path)
7. Implement `ModelResolver` (model → texture chain resolution)
8. Implement vanilla item scanner (reverse map: model → base item)
9. Implement `TextureConverter` (copy + remap)
10. Implement `ItemTextureAtlas` (item_texture.json generation)
11. Implement `ItemConverter` (Java items → Geyser definitions)
12. Implement `GeyserMappingBuilder` (full mapping file generation)

### Phase 1C: Deployment
13. Implement `GeyserDeployer` (detect + copy)
14. Add validation logging (summary of what was converted)

### Phase 1D: Testing
15. Write validation script for testbed harness
16. Run against 1.21.11 testbed
17. Fix issues found in validation
18. Manual Geyser test (if possible)

### Phase 2: Extended Content
19. `SoundConverter`
20. `LanguageConverter`
21. `AnimationConverter`
22. Extended validation

### Phase 3: Advanced
23. Custom block conversion
24. 3D geometry conversion
25. Equipment/attachable generation

---

## 7. File-by-File Implementation Notes

### Mix.java Changes

Uncomment and implement the bedrock conversion call:

```java
// After finalResourcePack is set (line ~258):
if (DefaultConfig.isBedrockConversionEnabled()) {
    BedrockConversion.generate(getOutputResourcePackFolder(), getOutputFolder());
}
```

This must run BEFORE the unzipped resource pack folder is deleted (line 255).

### BedrockConversion.java

```java
public class BedrockConversion {
    public static void generate(File mergedJavaPack, File outputDir) {
        Logger.info("Starting Bedrock resource pack conversion for Geyser...");

        // 1. Scan all Java item definitions
        Map<String, JavaItemDefinition> items = ItemConverter.scanItems(mergedJavaPack);

        // 2. Build reverse map: model → base vanilla item
        Map<String, String> modelToBaseItem = ItemConverter.buildReverseMap(mergedJavaPack);

        // 3. Resolve textures for each item
        Map<String, ResolvedTexture> textures = ModelResolver.resolveAll(items, mergedJavaPack);

        // 4. Build Bedrock pack
        File bedrockDir = new File(outputDir, "ResourcePackManager_Bedrock");
        BedrockPackBuilder builder = new BedrockPackBuilder(bedrockDir);
        builder.createManifest();
        builder.copyPackIcon(mergedJavaPack);

        // 5. Copy textures and build item_texture.json
        TextureConverter.copyAll(textures, mergedJavaPack, bedrockDir);
        builder.writeItemTexture(textures);

        // 6. Generate Geyser mappings
        GeyserMappingBuilder mappings = new GeyserMappingBuilder();
        for (var entry : items.entrySet()) {
            String baseItem = modelToBaseItem.getOrDefault(entry.getKey(), "minecraft:paper");
            mappings.addItem(baseItem, entry.getValue(), textures.get(entry.getKey()));
        }
        mappings.writeToFile(new File(outputDir, "rspm_geyser_mappings.json"));

        // 7. Zip Bedrock pack
        File bedrockZip = BedrockZip.zip(bedrockDir, outputDir);

        // 8. Deploy to Geyser
        GeyserDeployer.deploy(bedrockZip, new File(outputDir, "rspm_geyser_mappings.json"));

        Logger.info("Bedrock conversion complete: " + items.size() + " items converted.");
    }
}
```

---

## 8. Geyser Definition Type — New System Only

We ONLY generate `type: "definition"` mappings (Geyser Custom Items v2, 1.21.4+).
Legacy `type: "legacy"` with `custom_model_data` is NOT supported.

### Output Format

Every custom item produces a definition like:

```json
{
  "type": "definition",
  "model": "elitemobs:gear/bronze_sword",
  "bedrock_identifier": "elitemobs:bronze_sword",
  "display_name": "Bronze Sword",
  "bedrock_options": {
    "icon": "elitemobs.gear.bronze_sword",
    "display_handheld": true
  }
}
```

The `model` field comes from the Java item definition's model reference.
The `bedrock_identifier` is derived from the namespace + item name.

### display_handheld Detection

Items that should have `display_handheld: true` (rendered like tools):
- Base item is a tool/weapon: sword, axe, pickaxe, shovel, hoe, trident, bow, crossbow
- Model has `parent: "item/handheld"` or `parent: "item/handheld_rod"`

Items that should have `display_handheld: false`:
- Base item is non-tool: paper, emerald, diamond, nether_star, etc.
- Model has `parent: "item/generated"` or no handheld parent

**Detection method**: Check the vanilla model file's `parent` field:
- `"item/handheld"` or `"minecraft:item/handheld"` → `display_handheld: true`
- Everything else → `display_handheld: false`

---

## 9. Open Questions

1. **Base item detection accuracy**: How reliably can we determine which vanilla item
   a custom item is registered on? Need to test with real EliteMobs/FMM output.

2. **Geyser hot-reload**: Can Geyser reload mappings without a full restart? If yes,
   we could trigger that via Geyser API. If not, users must restart.

3. **Pack versioning**: Should we increment the Bedrock pack version on every server
   start (forces re-download) or only when content changes (requires content hashing)?

4. **Multi-Geyser platforms**: If both Geyser-Spigot and Geyser-Velocity exist (proxy
   setup), which one gets the files? Probably the local Spigot one.

5. **Item display names**: The Java resource pack's lang files have item names. Should
   we pull names from there, or let the user override in Geyser mappings config?

---

## Appendix: Conversion Examples

### Example 1: EliteMobs Coin

**Java input files**:
- `assets/elitemobs/items/coins/coin1.json` → references model `elitemobs:coins/coin1`
- `assets/elitemobs/models/coins/coin1.json` → texture `elitemobs:coins/coin1`
- `assets/elitemobs/textures/coins/coin1.png` → actual texture file
- `assets/minecraft/items/paper.json` → has select case for `elitemobs:coins/coin1`

**Bedrock output**:
- `textures/items/elitemobs/coins/coin1.png` (copied texture)
- item_texture.json entry: `"elitemobs.coins.coin1": { "textures": "textures/items/elitemobs/coins/coin1" }`
- Geyser mapping:
  ```json
  {
    "type": "definition",
    "model": "elitemobs:coins/coin1",
    "bedrock_identifier": "elitemobs:coin1",
    "bedrock_options": { "icon": "elitemobs.coins.coin1" }
  }
  ```
  Under `"minecraft:paper"` in the items object.

### Example 2: EliteMobs Bronze Sword

**Java input files**:
- `assets/elitemobs/items/gear/bronze_sword.json` → references model `elitemobs:gear/bronze_sword`
- `assets/elitemobs/models/gear/bronze_sword.json` → 3D model with texture `elitemobs:gear/bronze_sword`
- `assets/elitemobs/textures/gear/bronze_sword.png` → texture file
- `assets/minecraft/items/diamond_sword.json` → has reference to `elitemobs:gear/bronze_sword`

**Bedrock output**:
- `textures/items/elitemobs/gear/bronze_sword.png` (copied texture)
- item_texture.json entry: `"elitemobs.gear.bronze_sword": { "textures": "textures/items/elitemobs/gear/bronze_sword" }`
- Geyser mapping:
  ```json
  {
    "type": "definition",
    "model": "elitemobs:gear/bronze_sword",
    "bedrock_identifier": "elitemobs:bronze_sword",
    "bedrock_options": {
      "icon": "elitemobs.gear.bronze_sword",
      "display_handheld": true
    }
  }
  ```
  Under `"minecraft:diamond_sword"` in the items object.

### Example 3: Item with Select (multiple variants by item_model)

**Java input** (`assets/custom/items/bow_skin1.json`):
```json
{
  "model": {
    "type": "minecraft:model",
    "model": "custom:bow_skin1"
  }
}
```

**Java input** (`assets/custom/items/bow_skin2.json`):
```json
{
  "model": {
    "type": "minecraft:model",
    "model": "custom:bow_skin2"
  }
}
```

**Geyser output** (under `"minecraft:bow"` — base item determined from vanilla overrides):
```json
[
  {
    "type": "definition",
    "model": "custom:bow_skin1",
    "bedrock_identifier": "custom:bow_skin1",
    "bedrock_options": { "icon": "custom.bow_skin1", "display_handheld": true }
  },
  {
    "type": "definition",
    "model": "custom:bow_skin2",
    "bedrock_identifier": "custom:bow_skin2",
    "bedrock_options": { "icon": "custom.bow_skin2", "display_handheld": true }
  }
]
```
