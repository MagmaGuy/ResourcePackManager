package com.magmaguy.resourcepackmanager.bedrock.converter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.magmaguy.resourcepackmanager.bedrock.BedrockLog;
import com.magmaguy.resourcepackmanager.bedrock.generic.AssetResolver;
import com.magmaguy.resourcepackmanager.bedrock.generic.EquipmentSlotMapper;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Emits a worn-armor Bedrock {@code minecraft:attachable} that overrides the
 * rendered armor texture on the player body. Called from the items pipeline
 * (per {@code (item × base item)} mapping) so the attachable lives at the
 * SAME {@code bedrock_identifier} that Geyser will route to from the Java
 * {@code minecraft:item_model} predicate.
 *
 * <p><b>Why driven from the items pipeline (not from {@code equipment/*.json})</b>:
 * Geyser's custom-item-v2 {@code "model"} field matches against the Java item's
 * {@code minecraft:item_model} component — NOT the {@code equippable.asset_id}.
 * If we keyed our mappings on the equipment identifier, Geyser would never
 * route worn iron/etc. armor through them. Rainbow itself does this the same
 * way: see {@code BedrockAttachableContext.java:43-62} — for armor, Rainbow uses
 * {@code equippable.assetId()} ONLY to look up {@code EquipmentClientInfo.getLayers(...)}
 * for the texture path; the attachable identifier comes from the
 * {@code item_model}-derived bedrock identifier.
 *
 * <p><b>Reference implementation</b> — mirrors Rainbow's
 * {@code BedrockAttachable.equipment(...)} static builder
 * ({@code rainbow/src/main/java/org/geysermc/rainbow/pack/attachable/BedrockAttachable.java}
 * lines 42-69) for the attachable shape itself.
 *
 * <p>The emitted attachable's geometry references Bedrock-shipped vanilla
 * armor geometries by identifier (e.g. {@code geometry.player.armor.helmet}) —
 * these are baked into Bedrock's vanilla resource pack and don't need to be
 * redeclared. The {@code scripts.parent_setup} field emits the per-slot
 * Molang that hides the corresponding vanilla armor layer so the custom and
 * vanilla armor don't double-render.
 */
public final class EquipmentAttachableGenerator {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String ATTACHABLE_FORMAT_VERSION = "1.10.0";

    /** Bedrock-shipped vanilla armor geometry identifiers (Rainbow VanillaGeometries.java). */
    private static final Map<EquipmentSlotMapper.Slot, String> VANILLA_GEOMETRY =
            new EnumMap<>(EquipmentSlotMapper.Slot.class);
    static {
        VANILLA_GEOMETRY.put(EquipmentSlotMapper.Slot.HEAD, "geometry.player.armor.helmet");
        VANILLA_GEOMETRY.put(EquipmentSlotMapper.Slot.CHEST, "geometry.player.armor.chestplate");
        VANILLA_GEOMETRY.put(EquipmentSlotMapper.Slot.LEGS, "geometry.player.armor.leggings");
        VANILLA_GEOMETRY.put(EquipmentSlotMapper.Slot.FEET, "geometry.player.armor.boots");
    }
    private static final String GEOMETRY_ELYTRA = "geometry.elytra";

    /** Per-slot Molang that hides the vanilla armor render so our custom texture
     *  isn't drawn under a duplicate vanilla layer. Mirrors Rainbow's switch in
     *  {@code BedrockAttachable.equipment} (lines 43-48). */
    private static final Map<EquipmentSlotMapper.Slot, String> PARENT_SETUP =
            new EnumMap<>(EquipmentSlotMapper.Slot.class);
    static {
        PARENT_SETUP.put(EquipmentSlotMapper.Slot.HEAD, "variable.helmet_layer_visible = 0.0;");
        PARENT_SETUP.put(EquipmentSlotMapper.Slot.CHEST, "variable.chest_layer_visible = 0.0;");
        PARENT_SETUP.put(EquipmentSlotMapper.Slot.LEGS, "variable.leg_layer_visible = 0.0;");
        PARENT_SETUP.put(EquipmentSlotMapper.Slot.FEET, "variable.boot_layer_visible = 0.0;");
    }

    private EquipmentAttachableGenerator() {}

    /**
     * Items-pipeline-driven enrichment hook. Called per {@code (item × base item)}
     * mapping after the flat-icon (2D) path registers the mapping. If the item
     * looks like armor for the given base item, resolves the corresponding
     * Java equipment file, picks the right layer for the slot, copies the
     * armor texture into the Bedrock pack, and writes a worn-armor attachable
     * whose {@code description.identifier} EQUALS the supplied
     * {@code bedrockIdentifier} (i.e. the identifier the Geyser mapping points
     * at via {@code minecraft:item_model}).
     *
     * <p>Texture path convention (matches the previous standalone pipeline so
     * we don't churn pack contents): {@code textures/entity/equipment/<safeNs>/<materialStem>_<slot>.png}.
     *
     * <p>Attachable filesystem path: {@code attachables/<namespace>/<bedrockIdentifierPath>.json},
     * symmetric with FMM and generic-3D attachables (see
     * {@code FmmAttachableGenerator.writeAttachable} comments around line 201).
     *
     * @param bedrockIdentifier   Identifier the Geyser mapping points at, e.g.
     *                            {@code "elitemobs:gear_bronze_chestplate_icon__iron_chestplate"}.
     *                            The attachable's {@code description.identifier} is set to this
     *                            verbatim so Geyser's bedrock-identifier-based lookup finds it.
     * @param attachableOutputPath Filesystem stem under {@code attachables/} (no extension),
     *                            e.g. {@code "elitemobs/gear_bronze_chestplate_icon__iron_chestplate"}.
     * @param namespace           Source namespace (e.g. {@code "elitemobs"}). Used to locate the
     *                            equipment definition under {@code assets/<ns>/equipment/...}.
     * @param itemFilenameStem    Filename stem of the items definition (e.g.
     *                            {@code "bronze_chestplate"} for items/gear/bronze_chestplate.json).
     *                            Drives the {@link EquipmentSlotMapper#inferSlot} filename
     *                            heuristic and supplies the material stem when stripped of slot suffix.
     * @param baseItem            Target Java armor base item (e.g.
     *                            {@code "minecraft:iron_chestplate"}). Used to validate the
     *                            inferred slot matches and to detect the elytra special case.
     * @param resolver            Cache for {@code assets/<ns>/equipment/*.json} lookups.
     * @param mergedJavaPack      Root of the merged Java pack on disk (for texture-source lookup).
     * @param bedrockDir          Root of the Bedrock pack being written.
     * @return {@code true} if a worn-armor attachable was actually written.
     */
    public static boolean tryEnrichWithArmorAttachable(String bedrockIdentifier,
                                                       String attachableOutputPath,
                                                       String namespace,
                                                       String itemFilenameStem,
                                                       String baseItem,
                                                       AssetResolver resolver,
                                                       File mergedJavaPack,
                                                       File bedrockDir) {
        // 1) Infer slot. Use filename-only mode by passing a synthetic JsonObject
        //    with an empty `layers` so the mapper hits the filename branch and
        //    doesn't bail on null. The base item gives us a definitive fallback:
        //    if the filename heuristic returns nothing, derive slot from the base
        //    item itself (iron_chestplate → CHEST, etc.).
        EquipmentSlotMapper.Slot slot = inferSlotFromBaseItem(baseItem);
        if (slot == null) return false; // Not an armor base item; not our problem.
        boolean elytra = baseItem.equals("minecraft:elytra");

        // 2) Derive material stem from the items filename: strip slot-naming suffix.
        //    "bronze_chestplate" -> "bronze", "iron_helmet" -> "iron", etc. If the
        //    stem doesn't carry a slot suffix we fall back to using it verbatim
        //    (lets ad-hoc plugins point one items file at one equipment file).
        String materialStem = stripSlotSuffix(itemFilenameStem);

        // 3) Find the equipment definition file under the items namespace.
        String equipmentRef = namespace + ":" + materialStem;
        Optional<JsonObject> equipOpt = resolver.getEquipment(equipmentRef);
        if (equipOpt.isEmpty()) return false; // No matching equipment file -> nothing to enrich.
        JsonObject equip = equipOpt.get();
        if (!equip.has("layers") || !equip.get("layers").isJsonObject()) return false;
        JsonObject layers = equip.getAsJsonObject("layers");

        // 4) Pick the layer for THIS slot. HEAD/CHEST/FEET -> humanoid (or wings for elytra),
        //    LEGS -> humanoid_leggings.
        String layerKey;
        if (elytra && layers.has("wings")) {
            layerKey = "wings";
        } else if (slot == EquipmentSlotMapper.Slot.LEGS) {
            layerKey = "humanoid_leggings";
        } else {
            layerKey = "humanoid";
        }
        if (!layers.has(layerKey) || !layers.get(layerKey).isJsonArray()) return false;
        JsonArray layerArr = layers.getAsJsonArray(layerKey);
        if (layerArr.isEmpty()) return false;
        JsonElement first = layerArr.get(0);
        if (!first.isJsonObject()) return false;
        JsonObject firstObj = first.getAsJsonObject();
        if (!firstObj.has("texture") || !firstObj.get("texture").isJsonPrimitive()) return false;
        String textureRef = firstObj.get("texture").getAsString();

        // 5) Resolve textureRef -> source PNG on disk:
        //    "ns:stem" -> assets/<ns>/textures/entity/equipment/<layerKey>/<stem>.png
        int colon = textureRef.indexOf(':');
        String texNs = colon >= 0 ? textureRef.substring(0, colon) : "minecraft";
        String texStem = colon >= 0 ? textureRef.substring(colon + 1) : textureRef;
        File sourcePng = new File(mergedJavaPack,
                "assets/" + texNs + "/textures/entity/equipment/" + layerKey + "/" + texStem + ".png");
        if (!sourcePng.isFile()) {
            BedrockLog.warn("[BedrockConverter] Equipment texture not found: " + sourcePng.getPath()
                    + " (referenced by " + equipmentRef + " layer=" + layerKey + ")");
            return false;
        }

        // 6) Copy texture into the Bedrock pack. Path convention preserved from the
        //    prior standalone equipment pipeline so pack contents don't churn.
        String safeNs = namespace.replace(':', '_').replace('/', '_');
        String safeMaterial = materialStem.replace('/', '_');
        String slotKey = slot.name().toLowerCase();
        String bedrockTextureRel = "textures/entity/equipment/" + safeNs + "/" + safeMaterial + "_" + slotKey;
        File destPng = new File(bedrockDir, bedrockTextureRel + ".png");
        try {
            Files.createDirectories(destPng.getParentFile().toPath());
            // Verbatim copy: armor textures are flat 64x32 (or 64x64) skin-style
            // sheets, no flipbook cropping needed and no atlas stitching.
            Files.copy(sourcePng.toPath(), destPng.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            BedrockLog.warn("[BedrockConverter] Failed to copy equipment texture "
                    + sourcePng.getPath() + " -> " + destPng.getPath() + ": " + e.getMessage());
            return false;
        }

        // 7) Write the worn-armor attachable, identifier = supplied bedrockIdentifier.
        File attachableFile = new File(bedrockDir, "attachables/" + attachableOutputPath + ".json");
        return writeAttachable(attachableFile, bedrockIdentifier, slot, elytra, bedrockTextureRel);
    }

    /**
     * Filename-suffix strip: "bronze_chestplate" → "bronze", "iron_helmet" → "iron", etc.
     * If no recognised slot suffix is present, returns the stem unchanged.
     */
    private static String stripSlotSuffix(String itemFilenameStem) {
        if (itemFilenameStem == null) return "";
        String stem = itemFilenameStem;
        int slashIdx = stem.lastIndexOf('/');
        if (slashIdx >= 0) stem = stem.substring(slashIdx + 1);
        String lower = stem.toLowerCase();
        for (String suffix : SLOT_SUFFIXES) {
            if (lower.endsWith("_" + suffix)) {
                return stem.substring(0, stem.length() - suffix.length() - 1);
            }
        }
        return stem;
    }

    /** Known slot suffixes used by {@link #stripSlotSuffix} (lowercase). */
    private static final String[] SLOT_SUFFIXES = {
            "helmet", "chestplate", "leggings", "boots", "cap", "hood", "crown",
            "tunic", "cloak", "robe", "pants", "trousers", "shoes", "elytra", "wings"
    };

    /**
     * Map a vanilla armor base item to its slot. Returns null if the base item
     * isn't an armor item (caller treats this as "no enrichment needed").
     */
    private static EquipmentSlotMapper.Slot inferSlotFromBaseItem(String baseItem) {
        if (baseItem == null) return null;
        String b = baseItem.replace("minecraft:", "");
        if (b.endsWith("_helmet") || b.equals("turtle_helmet")) return EquipmentSlotMapper.Slot.HEAD;
        if (b.endsWith("_chestplate") || b.equals("elytra")) return EquipmentSlotMapper.Slot.CHEST;
        if (b.endsWith("_leggings")) return EquipmentSlotMapper.Slot.LEGS;
        if (b.endsWith("_boots")) return EquipmentSlotMapper.Slot.FEET;
        return null;
    }

    /**
     * Writes one attachable JSON file. Shape mirrors Rainbow's
     * {@code BedrockAttachable.equipment(...)} output:
     *
     * <pre>{@code
     * {
     *   "format_version": "1.10.0",
     *   "minecraft:attachable": {
     *     "description": {
     *       "identifier":   "<ns>:<equip>_<slot>__<baseSafe>",
     *       "materials":    { "default": "armor", "enchanted": "armor_enchanted" },
     *       "textures":     { "default": "textures/.../...",
     *                         "enchanted": "textures/misc/enchanted_actor_glint" },
     *       "geometry":     { "default": "geometry.player.armor.<slot>" },
     *       "scripts":      { "parent_setup": "variable.helmet_layer_visible = 0.0;" },
     *       "render_controllers": [ "controller.render.armor" ]
     *     }
     *   }
     * }
     * }</pre>
     */
    private static boolean writeAttachable(File outputFile,
                                           String identifier,
                                           EquipmentSlotMapper.Slot slot,
                                           boolean glider,
                                           String bedrockTextureRel) {
        JsonObject description = new JsonObject();
        description.addProperty("identifier", identifier);

        JsonObject materials = new JsonObject();
        materials.addProperty("default", glider ? "elytra" : "armor");
        materials.addProperty("enchanted", glider ? "elytra_glint" : "armor_enchanted");
        description.add("materials", materials);

        JsonObject textures = new JsonObject();
        textures.addProperty("default", bedrockTextureRel);
        textures.addProperty("enchanted", "textures/misc/enchanted_actor_glint");
        description.add("textures", textures);

        JsonObject geometry = new JsonObject();
        geometry.addProperty("default", glider ? GEOMETRY_ELYTRA : VANILLA_GEOMETRY.get(slot));
        description.add("geometry", geometry);

        // scripts.parent_setup: per-slot Molang that hides the vanilla armor layer
        // so we don't render both at once. Rainbow emits an empty string for the
        // glider/unknown slot fallback; we mirror that (parent_setup gets omitted
        // when null so the glider attachable just doesn't toggle a layer).
        String setup = PARENT_SETUP.get(slot);
        if (setup != null && !setup.isEmpty()) {
            JsonObject scripts = new JsonObject();
            scripts.addProperty("parent_setup", setup);
            description.add("scripts", scripts);
        }

        JsonArray renderControllers = new JsonArray();
        renderControllers.add("controller.render.armor");
        description.add("render_controllers", renderControllers);

        JsonObject attachable = new JsonObject();
        attachable.add("description", description);

        JsonObject root = new JsonObject();
        root.addProperty("format_version", ATTACHABLE_FORMAT_VERSION);
        root.add("minecraft:attachable", attachable);

        try {
            Files.createDirectories(outputFile.getParentFile().toPath());
            try (FileWriter w = new FileWriter(outputFile, StandardCharsets.UTF_8)) {
                GSON.toJson(root, w);
            }
            return true;
        } catch (IOException e) {
            BedrockLog.warn("[BedrockConverter] Failed to write equipment attachable "
                    + outputFile.getPath() + ": " + e.getMessage());
            return false;
        }
    }
}
