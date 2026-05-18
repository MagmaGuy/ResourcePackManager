# Bedrock Pack Validation Specification

> Defines all checks that the validation script must perform on generated Bedrock
> resource packs and Geyser mappings.

## 1. Structural Checks

### manifest.json
- [ ] File exists at pack root
- [ ] Valid JSON
- [ ] `format_version` is 2
- [ ] `header.uuid` is valid UUID format
- [ ] `header.version` is array of 3 integers
- [ ] `header.min_engine_version` is array of 3 integers
- [ ] `header.name` is non-empty string
- [ ] `modules` array has at least one entry
- [ ] Module type is `"resources"`
- [ ] Module UUID differs from header UUID
- [ ] Module UUID is valid UUID format

### pack_icon.png
- [ ] File exists at pack root
- [ ] Is valid PNG image
- [ ] Is square (width == height)

### item_texture.json
- [ ] File exists at `textures/item_texture.json`
- [ ] Valid JSON
- [ ] Has `resource_pack_name` field
- [ ] Has `texture_name` field equal to `"atlas.items"`
- [ ] Has `texture_data` object
- [ ] `texture_data` is non-empty (if custom items exist)

## 2. Texture Integrity Checks

For every entry in `item_texture.json.texture_data`:
- [ ] Referenced texture file exists (append `.png` to path)
- [ ] Texture file is valid PNG
- [ ] Key follows naming convention (dots as separators, no colons)

## 3. Geyser Mappings Checks

### rspm_geyser_mappings.json
- [ ] File exists
- [ ] Valid JSON
- [ ] `format_version` is 2
- [ ] `items` object is present and non-empty

### Per-Item Definition
- [ ] `bedrock_identifier` is present and non-empty
- [ ] `bedrock_identifier` does NOT start with `minecraft:`
- [ ] No duplicate `bedrock_identifier` values across ALL definitions
- [ ] `type` is `"definition"` (RSPM only generates this type)
- [ ] `model` field is present and non-empty
- [ ] If `bedrock_options.icon` exists: matches a key in `item_texture.json.texture_data`

### Base Item Keys
- [ ] All keys in `items` object are valid Minecraft item identifiers
- [ ] Keys use `minecraft:` namespace

## 4. Cross-Reference Checks

### Every model referenced in Geyser mappings:
- [ ] Corresponding model file exists in the Java merged pack
  (at `assets/<namespace>/models/<path>.json`)

### Every texture referenced in item_texture.json:
- [ ] Corresponding source texture exists in the Java merged pack
  (at `assets/<namespace>/textures/<path>.png`)

### Every definition model reference:
- [ ] The `model` value matches a custom item definition in `assets/<namespace>/items/`

## 5. Quantitative Checks

- [ ] Number of Geyser item definitions >= number of custom models found in vanilla overrides
- [ ] Number of texture entries == number of unique textures across all definitions
- [ ] No orphaned textures (textures in Bedrock pack not referenced by any mapping)
- [ ] No orphaned mappings (mappings referencing textures not in the pack)

## 6. Expected Output for Current Testbed (1.21.11)

Based on the actual mixer output from EliteMobs + FreeMinecraftModels:

### Expected Custom Items (EliteMobs)

| Category | Items | Base Item |
|----------|-------|-----------|
| Coins | coin1-4, goldenquestionmark | paper, emerald, nether_star |
| Swords | bronze/corrupted/living/palladium/ultimatium_sword + specials | diamond_sword, netherite_sword |
| Axes | bronze/corrupted/living/palladium/ultimatium_axe | diamond_axe |
| Scythes | bronze/corrupted/living/palladium/ultimatium_scythe | diamond_hoe |
| Bows | bronze/corrupted/living/palladium/ultimatium_bow | ??? (check) |
| Crossbows | bronze/corrupted/living/palladium/ultimatium_crossbow | ??? (check) |
| Helmets | bronze/corrupted/living/palladium/ultimatium_helmet | iron_horse_armor |
| Tridents | bronze/corrupted/living/palladium/ultimatium_trident | diamond_sword |
| UI elements | redcross, goldenquestionmark, etc. | barrier, paper |
| Scroll | elitescroll | ??? |

### Expected Texture Count
- EliteMobs: ~50+ unique textures (coins, gear, UI, entities, GUI)
- FreeMinecraftModels: ~20+ item textures (furniture models)
- Total: ~70+ item_texture.json entries

### Expected Geyser Definition Count
- Should match the total number of unique model references across all overrides
- Estimated: ~100-200 definitions (many overrides are duplicated across merge iterations)

## 7. Smoke Test Integration

### Adding to Invoke-SmokeMatrix.ps1

The bedrock validation should be added as a post-boot validation step:

```powershell
# After boot validation passes:
if ($bedrockConversionEnabled) {
    $bedrockPackPath = Join-Path $pluginDir "output" "ResourcePackManager_Bedrock.zip"
    $geyserMappingsPath = Join-Path $pluginDir "output" "rspm_geyser_mappings.json"

    # Check files exist
    Assert-FileExists $bedrockPackPath "Bedrock pack"
    Assert-FileExists $geyserMappingsPath "Geyser mappings"

    # Unzip and validate
    $unzipDir = Join-Path $tempDir "bedrock_validate"
    Expand-Archive $bedrockPackPath $unzipDir

    # Run all structural checks
    Validate-BedrockManifest (Join-Path $unzipDir "manifest.json")
    Validate-ItemTexture (Join-Path $unzipDir "textures" "item_texture.json")
    Validate-GeyserMappings $geyserMappingsPath (Join-Path $unzipDir "textures" "item_texture.json")
    Validate-TextureIntegrity $unzipDir
    Validate-CrossReferences $geyserMappingsPath $unzipDir $mergedPackDir
}
```

## 8. Manual Testing Checklist

For human verification with a Bedrock client + Geyser:

### Setup
- [ ] Geyser installed with `enable-custom-content: true`
- [ ] Generated Bedrock pack in Geyser `packs/` folder
- [ ] Generated mappings in Geyser `custom_mappings/` folder
- [ ] Server restarted after file placement

### Bedrock Client Tests
- [ ] Pack downloads automatically when connecting
- [ ] No errors in Geyser console about malformed mappings
- [ ] `/give` a custom item (e.g., EliteMobs coin) shows correct icon
- [ ] Custom item name displays correctly
- [ ] Tool items render correctly when held (display_handheld)
- [ ] Non-tool items render correctly when held
- [ ] Custom sounds play (Phase 2)
- [ ] Items function correctly (food, equipment, etc.)

### Regression Tests
- [ ] Java client still works correctly (no impact)
- [ ] Java resource pack merge is unaffected
- [ ] Server startup time acceptable (conversion adds < 5 seconds)
- [ ] No file lock issues on Windows
