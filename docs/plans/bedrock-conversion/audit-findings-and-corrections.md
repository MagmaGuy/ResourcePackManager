# Audit Findings & Corrections

> Post-audit corrections to the bedrock conversion design documents.
> All issues identified during the automated audit and testbed verification.

## Critical Corrections

### 1. FreeMinecraftModels NOT in Testbed Mixer Output

**Finding**: The 1.21.11 testbed's mixer output contains ONLY `elitemobs/` and `minecraft/`
namespaces. FreeMinecraftModels is in the RSPM priority config but the FMM plugin was not
generating a resource pack zip in this testbed environment.

**Impact**: Phase 1 development should focus on EliteMobs data. FMM will be tested separately
once FMM is generating output in a testbed.

**Action**: Not a bug in the plan — just means initial testing uses EliteMobs only. FMM uses
the same formats (items/*.json with model references), so the converter will work for both.

### 2. Texture Name ≠ Model Path (CONFIRMED)

**Finding**: Model paths do NOT match texture filenames:
- Model file: `models/gear/bronze_sword.json`
- Texture reference in model: `"elitemobs:items/bronzesword"` (no underscores, different path)
- Actual texture file: `textures/items/bronzesword.png`

**Impact**: The converter MUST follow the model → texture resolution chain. Cannot assume
model filename == texture filename.

**Corrected resolution chain**:
```
1. Item definition → model reference (e.g., "elitemobs:gear/bronze_sword")
2. Find model file at: assets/elitemobs/models/gear/bronze_sword.json
3. Read model JSON → textures field → "0": "elitemobs:items/bronzesword"
4. Resolve texture: assets/elitemobs/textures/items/bronzesword.png
5. Copy to Bedrock: textures/items/elitemobs/items/bronzesword.png
6. Register in item_texture.json: "elitemobs.items.bronzesword"
```

**The icon key is derived from the TEXTURE reference**, not the model path.

### 3. Animated Item Textures (.mcmeta) Exist

**Finding**: Many EliteMobs item textures have `.mcmeta` sidecar files:
- `bronzesword.png.mcmeta`
- `bronzeaxe.png.mcmeta`
- `bronzebow.png.mcmeta`
- Equipment textures also have `.mcmeta` files

**Impact for Phase 1**: These animated textures will appear STATIC on Bedrock clients.
The PNG file's first frame will be used as the icon. This is acceptable for Phase 1.

**Phase 2 action**: Convert `.mcmeta` to `flipbook_textures.json` entries for item textures
that appear in the Bedrock pack.

### 4. Output File Naming — Standardized

**Inconsistency found**: Design doc uses `rspm_geyser_mappings.json`, reference doc uses `rspm_items.json`.

**Standardized names**:
- Bedrock resource pack: `ResourcePackManager_Bedrock.zip`
- Geyser item mappings: `rspm_geyser_mappings.json`
- Output folder for Bedrock pack assembly: `ResourcePackManager_Bedrock/`

### 5. Duplicate Model References in Vanilla Overrides

**Finding**: `diamond_sword.json` has 100+ overrides but the same model references
appear 10 times identically (from multiple merge iterations).

**Correction**: The base item scanner must **deduplicate** overrides by model reference.
We only care about the `model → base_item` mapping, not the CMD values themselves
(since we only generate `type: "definition"` mappings, not legacy CMD mappings).

**Algorithm**:
```
Set<String> seenModels = new HashSet<>();
for each override in model.overrides:
    if (seenModels.contains(override.model)) continue;
    seenModels.add(override.model);
    reverseMap[override.model] = vanillaItemName;
```

### 6. UUID Generation Algorithm

**Complete specification**:
```java
// Fixed namespace UUID for RSPM Bedrock packs (generated once, never changes)
private static final UUID NAMESPACE_UUID = UUID.fromString("a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d");

// Header UUID: based on pack name
UUID headerUuid = UUID.nameUUIDFromBytes(
    ("rspm-bedrock-header-" + packContentHash).getBytes(StandardCharsets.UTF_8)
);

// Module UUID: based on pack name + module suffix
UUID moduleUuid = UUID.nameUUIDFromBytes(
    ("rspm-bedrock-module-" + packContentHash).getBytes(StandardCharsets.UTF_8)
);

// packContentHash: SHA1 of all file paths + sizes in the Bedrock pack (sorted)
// This ensures UUID only changes when pack content changes
```

Note: `UUID.nameUUIDFromBytes` generates UUID v3 (MD5-based). This is simpler and
sufficient for our needs. UUID v5 (SHA1-based) would require a library.

### 7. Mix.java Hook Point — Verified

**Current code** (lines 240-260):
```java
// Line 244: Final zip is created
finalResourcePack = file;

// Line 255: Unzipped directory is DELETED
recursivelyDeleteDirectory(file);

// Lines 258-259: Commented placeholder
// ADD THE BEDROCK CONVERSION CALL HERE - AFTER finalResourcePack IS SET:
// generateBedrockResourcePack();
```

**Problem**: The conversion MUST run BEFORE line 255 (directory deletion), but AFTER the
zip is created. However, the current loop at line 243 iterates output folder files and
deletes non-zip files.

**Corrected hook point**: Insert the bedrock conversion call at line 257, before the
existing commented placeholder, which is AFTER the cleanup loop:

```java
// BUT WAIT: the unzipped folder is ALREADY deleted by line 255!
```

**CRITICAL ISSUE**: The unzipped resource pack folder is deleted in the cleanup loop
(lines 243-256). The bedrock conversion needs the unzipped content to scan items/models/textures.

**Two options**:
1. Move the conversion call INSIDE the loop, before the delete (messy)
2. **Better**: Run the conversion BEFORE the cleanup loop, using `getOutputResourcePackFolder()`
   which still exists at that point

**Corrected integration point**: Insert BEFORE line 243:
```java
// After zip is created but before cleanup
if (DefaultConfig.isBedrockConversionEnabled()) {
    BedrockConversion.generate(getOutputResourcePackFolder(), getOutputFolder());
}

// Then the existing cleanup loop runs
for (File file : getOutputFolder().listFiles()) {
    ...
}
```

**VERIFIED correct integration point** (after reading Mix.java lines 215-260):

The flow is:
```
Line 222: ZipFile.zip(getOutputResourcePackFolder(), ...) — Java zip created
Lines 227-241: Optional resource pack rerouting
Line 243-256: Cleanup loop — finds zip, deletes everything else (INCLUDING unzipped folder!)
Lines 258-259: Commented bedrock conversion placeholder (TOO LATE — folder is gone!)
```

**The correct hook is between line 241 and line 243** — after zip creation and rerouting,
but BEFORE the cleanup loop deletes the unzipped folder:

```java
// Line 241: } (end of rerouting block)

// ★ INSERT BEDROCK CONVERSION HERE ★
if (DefaultConfig.isBedrockConversionEnabled()) {
    BedrockConversion.generate(getOutputResourcePackFolder(), getOutputFolder());
}

// Line 243: for (File file : getOutputFolder().listFiles()) { // cleanup loop
```

The commented placeholder at lines 258-259 is WRONG — the unzipped folder is already
deleted by that point. The placeholder should be moved to line 242.

### 8. Icon Naming Convention — Standardized

For a texture reference `elitemobs:items/bronzesword`:
- **Icon key**: `elitemobs.items.bronzesword` (colons→periods, slashes→periods)
- **item_texture.json texture path**: `textures/items/elitemobs/items/bronzesword`
- **Bedrock file path**: `textures/items/elitemobs/items/bronzesword.png`

For a texture reference `freeminecraftmodels:bfp_bed/fmm_bfp_bed`:
- **Icon key**: `freeminecraftmodels.bfp_bed.fmm_bfp_bed`
- **item_texture.json texture path**: `textures/items/freeminecraftmodels/bfp_bed/fmm_bfp_bed`
- **Bedrock file path**: `textures/items/freeminecraftmodels/bfp_bed/fmm_bfp_bed.png`

**Rule**: ALL separators (colons and slashes) become periods in icon keys. The texture
path preserves directory structure using slashes.

### 9. Sounds.json Format — Both Formats Valid

**Format 1** (string array — used by EliteMobs testbed):
```json
{
  "treasure_chest.open": {
    "sounds": ["custom/treasure_chest_open", "custom/treasure_chest_open_2"]
  }
}
```

**Format 2** (object array — used by some packs):
```json
{
  "entity.custom.hurt": {
    "sounds": [
      { "name": "custom/hurt1", "volume": 0.5 }
    ],
    "subtitle": "subtitle.custom.hurt"
  }
}
```

Both are valid Java resource pack formats. The converter must handle both.

### 10. Phase 1 Scope — Explicit Boundaries

**Phase 1 WILL**:
- Convert custom item textures to Bedrock format
- Generate item_texture.json
- Generate Geyser custom item mappings (both legacy CMD and new-format definitions)
- Generate manifest.json
- Generate pack_icon.png
- Deploy to Geyser folders
- Detect and log (but NOT convert) sounds, fonts, equipment, animations

**Phase 1 will NOT**:
- Convert sounds → sound_definitions.json
- Convert language files → .lang
- Convert animations → flipbook_textures.json
- Convert fonts
- Convert 3D geometry
- Convert equipment/armor attachables
- Convert custom blocks
