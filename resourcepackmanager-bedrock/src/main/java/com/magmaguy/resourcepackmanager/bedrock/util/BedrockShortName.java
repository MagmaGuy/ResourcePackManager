package com.magmaguy.resourcepackmanager.bedrock.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Generates short, opaque, deterministic identifiers for the Bedrock pack's
 * internal file paths and Geyser mapping identifiers.
 *
 * <h2>Why this exists</h2>
 * Bedrock resource packs warn ("This will cause problems on some Bedrock
 * platforms") when any file inside the pack has a path of 80+ chars. The
 * previous naming scheme {@code textures/items/<namespace>__<longJavaPath>.png}
 * routinely produced 100+ char paths because Java item paths embedded the
 * full plugin/model/bone hierarchy (e.g.
 * {@code freeminecraftmodels__fmm_craftenmine_basic_item_pack_velocity_enhancer_mk2_crossbow_charged_arrow.png}
 * = 116 chars). At a few hundred such files per pack, the warning floods the
 * Geyser log and risks rejection on consoles/mobile clients.
 *
 * <h2>What this does</h2>
 * Replaces those long human-readable stems with an 8-char hex hash derived
 * from the source identifier. The hash is:
 * <ul>
 *   <li><b>Deterministic</b> — same input always produces same output, so
 *       Bedrock client caches stay valid across re-merges and so identifiers
 *       on the proxy match identifiers on the backends.</li>
 *   <li><b>Short</b> — 8 hex chars (32-bit prefix of SHA-256) keeps file
 *       paths well under 80 chars and Geyser mapping JSON compact.</li>
 *   <li><b>Opaque</b> — reveals nothing about the source plugin, model name,
 *       or bone name. The user explicitly does not want pack inspection to
 *       trivially identify the source (e.g. "fmm" prefix would be a
 *       dead giveaway).</li>
 *   <li><b>Collision-resistant in practice</b> — 32-bit space holds 4.3
 *       billion unique IDs; birthday-paradox 1%-collision threshold sits
 *       around ~10k mappings, well above any realistic pack size.</li>
 * </ul>
 *
 * <h2>Hash recipe</h2>
 * SHA-256 of the input UTF-8 bytes, then hex-encode the first 4 bytes.
 * Lower-case alphanumeric, always 8 chars, suitable for use as a file-path
 * stem or as the path-segment of a Bedrock identifier.
 *
 * <h2>Namespace</h2>
 * {@link #BEDROCK_NAMESPACE} is the single-letter Bedrock identifier
 * namespace used for everything RSPM emits. Picked for length, not branding;
 * one letter keeps the {@code namespace:path} identifier as short as possible
 * (e.g. {@code r:a7b3c1d8} = 10 chars). The letter itself doesn't decode to
 * anything meaningful — deliberately not "f" (FMM), "rspm" or similar.
 */
public final class BedrockShortName {

    /**
     * One-letter Bedrock identifier namespace for all RSPM-generated
     * attachables and item mappings. See class javadoc for rationale.
     */
    public static final String BEDROCK_NAMESPACE = "r";

    private BedrockShortName() {}

    /**
     * Stable short name keyed by a single source string.
     *
     * <p>Use this for per-model assets (textures, geometries, animations)
     * where every (base-item, predicate) variant of the same source model
     * should share the same on-disk file. The reverse holds: if two source
     * model refs differ, their short names will differ (collisions only
     * via the birthday paradox at &gt;~10k entries).
     */
    public static String forModel(String modelRef) {
        return shortHash(modelRef == null ? "" : modelRef);
    }

    /**
     * Stable short name keyed by the (model, base-item) pair, with no predicate
     * component. Equivalent to {@link #forBaseMapping(String, String, String)}
     * with an empty signature. Retained so unconditional callers stay terse and
     * so historical identifiers (no predicate) are produced byte-for-byte as before.
     */
    public static String forBaseMapping(String modelRef, String baseItem) {
        return forBaseMapping(modelRef, baseItem, null);
    }

    /**
     * Stable short name keyed by the (model, base-item, predicate-signature) triple.
     *
     * <p>Use this for the attachable file path and the Geyser
     * {@code bedrock_identifier}, both of which are per-mapping not
     * per-model. Two mappings that share a Java model but target different
     * Bedrock base items will get different short names here, so each
     * attachable lives at its own file path and gets its own identifier
     * in the Geyser mappings.
     *
     * <h3>Why the predicate signature is part of the key</h3>
     * A single Java model under a single base item can produce several Geyser
     * definitions that differ only by predicate — the canonical case is a
     * crossbow whose {@code charge_type=arrow} and {@code charge_type=rocket}
     * branches resolve to the same custom model. Geyser keys every custom item
     * definition by its {@code bedrock_identifier} and rejects duplicates
     * ("conflicts with another custom item definition with the same bedrock
     * identifier"), silently dropping all but the first. Folding the predicate
     * signature into the hash gives each predicate variant a distinct identifier
     * (and a distinct attachable file), so Geyser registers them all.
     *
     * <p>Pass {@code null} or an empty string for unconditional mappings; the
     * hash input is then identical to the legacy (model, base) form, so existing
     * Bedrock client caches for predicate-free items stay valid across upgrades.
     */
    public static String forBaseMapping(String modelRef, String baseItem, String predicateSignature) {
        String key = (modelRef == null ? "" : modelRef) + "|" + (baseItem == null ? "" : baseItem);
        if (predicateSignature != null && !predicateSignature.isEmpty()) {
            key = key + "|" + predicateSignature;
        }
        return shortHash(key);
    }

    /**
     * Builds a full Bedrock identifier from a short name: {@code "r:<name>"}.
     * Centralised here so the namespace can be retuned in one place if a
     * collision with another pack is ever reported.
     */
    public static String bedrockIdentifier(String shortName) {
        return BEDROCK_NAMESPACE + ":" + shortName;
    }

    /**
     * Core hash: SHA-256, first 4 bytes encoded as 8 chars, lower-case.
     * Always returns 8 chars and always starts with a letter ({@code a}–{@code p}).
     *
     * <h3>Why the first char is forced to a letter</h3>
     * Bedrock's item-ID parser interprets {@code namespace:<all-digits>} as
     * {@code namespace:aux_value} (the legacy 1.13-era item damage-value
     * lookup) rather than as {@code namespace:path}. When the path component
     * is all digits, the parser rejects the identifier with
     * {@code [Json][error]-Invalid aux value in item ID "..."}, and the
     * Bedrock client never renders the attachable. Plain 8-hex-char output
     * lands on all-digit values by chance for a small fraction of hashes,
     * which we observed in production as scattered render misses.
     *
     * <p>To avoid this without giving up entropy, the first nibble (4 bits)
     * is mapped {@code 0..f → a..p} (16 letters). The remaining 7 nibbles
     * stay as hex, preserving the full 32 bits of hash entropy. Result:
     * always 8 chars, always starts with a letter, no all-digit outputs
     * are possible.
     */
    public static String shortHash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(8);
            // First nibble → 'a'..'p' (one of 16 letters). Maps 0=a, 1=b, ..., 15=p.
            int firstNibble = (digest[0] >> 4) & 0xf;
            sb.append((char) ('a' + firstNibble));
            // Second nibble → hex (still 4 bits of entropy from byte 0).
            int secondNibble = digest[0] & 0xf;
            sb.append(Integer.toHexString(secondNibble));
            // Bytes 1, 2, 3 → 6 more hex chars. Total: 1 + 1 + 6 = 8 chars,
            // 4 + 4 + 24 = 32 bits of entropy.
            for (int i = 1; i < 4; i++) {
                sb.append(String.format("%02x", digest[i] & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available; this should never happen on a standard JRE.", e);
        }
    }
}
