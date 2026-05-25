package com.magmaguy.resourcepackmanager.bedrock.util;

import com.magmaguy.resourcepackmanager.bedrock.BedrockLog;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Emits {@code materials/rspm.material} into the generated Bedrock pack. This file
 * defines a custom unlit material that {@code FmmAttachableGenerator} references
 * from every emitted attachable.
 *
 * <p><b>Why a custom material:</b> FMM renders models via packet-level armor stands
 * whose entity position frequently sits inside a solid block (the bone's pivot
 * lands at the floor while the model mesh sticks out above). Bedrock samples
 * world light at the entity's position — not at the rendered mesh's position —
 * so opaque pixels go pitch-black even though the visual mesh is in lit space.
 * Java doesn't have this problem because Java samples light at the rendered
 * geometry.</p>
 *
 * <p>The stock {@code entity_emissive_alpha} we used before mixes emissive and
 * sampled world light alpha-proportionally, so opaque pixels still partially
 * receive zero-light from the entity position and the mesh dims toward black.
 * Adding {@code USE_ONLY_EMISSIVE} short-circuits the world-light branch — the
 * albedo is treated as pure emissive, world light is ignored end-to-end, alpha
 * cutout still works because we inherit the parent's ALPHA_TEST state.</p>
 *
 * <p>This is exactly what vanilla Bedrock does for enderman/spider/phantom
 * invisible-eye glow (their stock material declarations
 * {@code enderman_invisible:entity_emissive_alpha}, etc. all carry the same
 * {@code "+defines": ["USE_ONLY_EMISSIVE"]}). The inheritance form
 * {@code "child:parent"} is the canonical, supported way to author Bedrock
 * materials per Microsoft Learn (Materials and Material Files, 2026 docs).
 * The older "errors silently in 1.16.100+" lore in this codebase was wrong —
 * what broke in 1.16.100 was a different thing (deprecated standalone-material
 * keys like {@code vertexShader} / {@code vertexFields} / {@code msaaSupport}).
 * Inheritance itself is fine.</p>
 */
public final class BedrockMaterialEmitter {

    private BedrockMaterialEmitter() {}

    /**
     * Material identifier referenced from every attachable {@code materials.default} field.
     * Resolution: parent {@code entity_emissive_alpha} → +define {@code USE_ONLY_EMISSIVE}.
     */
    public static final String UNLIT_MATERIAL = "rspm_unlit";

    /**
     * Same idea for the enchanted-overlay material — glint is an additive overlay,
     * and we want it to read full-brightness too so that enchanted models inside
     * solid blocks aren't a bright-glint-on-pitch-black sandwich.
     */
    public static final String UNLIT_GLINT_MATERIAL = "rspm_unlit_glint";

    private static final String MATERIAL_JSON = """
            {
              "materials": {
                "version": "1.0.0",
                "rspm_unlit:entity_emissive_alpha": {
                  "+defines": [ "USE_ONLY_EMISSIVE" ]
                },
                "rspm_unlit_glint:entity_alphatest_glint": {
                  "+defines": [ "USE_ONLY_EMISSIVE" ]
                }
              }
            }
            """;

    /**
     * Write {@code materials/rspm.material} into the pack staging directory.
     * Called once per {@link com.magmaguy.resourcepackmanager.bedrock.BedrockConversion#generate}
     * call, before attachables are emitted (their material references will be
     * resolved by Bedrock's material loader at client load time, so emit order
     * within the pack zip doesn't matter — but we still write up front so a
     * partial run leaves a coherent pack rather than orphan references).
     */
    public static void emit(File bedrockPackDir) {
        File out = new File(bedrockPackDir, "materials/rspm.material");
        try {
            Files.createDirectories(out.getParentFile().toPath());
            Files.writeString(out.toPath(), MATERIAL_JSON, StandardCharsets.UTF_8);
        } catch (IOException e) {
            BedrockLog.warn("[BedrockConverter] Failed to write " + out.getPath()
                    + " — FMM models inside solid blocks will render dark on Bedrock: " + e.getMessage());
        }
    }
}
