package com.magmaguy.resourcepackmanager.bedrock.generic;

import java.util.List;
import java.util.Optional;

/**
 * Filename-pattern → base-item-set heuristic for weapons/tools (and armor as fallback).
 * Order matters: first matching rule wins. See Option J plan, "Filename heuristic table".
 *
 * <p>Returns {@link Optional#empty()} when no rule matches; the caller should fall back
 * to the generic fallback set ({@link #genericFallback()}).
 */
public final class FilenameHeuristic {

    private FilenameHeuristic() {}

    /** Tool/weapon patterns (armor handled by {@link EquipmentSlotMapper} when an equipment file exists). */
    private static final List<Rule> WEAPON_RULES = List.of(
            // pickaxe must come before axe
            Rule.simple("pickaxe", null, woodenToNetherite("pickaxe")),
            Rule.simple("axe", "pickaxe", woodenToNetherite("axe")),
            Rule.any(List.of("sword", "blade", "katana"), null, woodenToNetherite("sword")),
            Rule.any(List.of("shovel", "spade"), null, woodenToNetherite("shovel")),
            Rule.simple("hoe", "shoe", woodenToNetherite("hoe")),
            Rule.simple("crossbow", null, List.of("minecraft:crossbow")),
            // bow comes after crossbow; guard against "elbow" / "rainbow"
            Rule.bow(),
            Rule.simple("trident", null, List.of("minecraft:trident")),
            Rule.simple("mace", null, List.of("minecraft:mace")),
            Rule.any(List.of("rod", "fishing"), null, List.of("minecraft:fishing_rod")),
            Rule.simple("shield", null, List.of("minecraft:shield")),
            Rule.any(List.of("stick", "wand", "staff", "scepter"), null, List.of("minecraft:stick")),
            Rule.simple("scythe", null, List.of("minecraft:netherite_hoe")),
            Rule.any(List.of("pike", "spear", "lance"), null, List.of("minecraft:trident"))
    );

    /** Armor patterns (used when no equipment file is present). */
    private static final List<Rule> ARMOR_RULES = List.of(
            Rule.any(List.of("helmet", "cap", "hood", "crown"), null,
                    EquipmentSlotMapper.baseItemsFor(EquipmentSlotMapper.Slot.HEAD)),
            Rule.any(List.of("chestplate", "tunic", "cloak", "robe"), null,
                    EquipmentSlotMapper.baseItemsFor(EquipmentSlotMapper.Slot.CHEST)),
            Rule.any(List.of("leggings", "pants", "trousers"), null,
                    EquipmentSlotMapper.baseItemsFor(EquipmentSlotMapper.Slot.LEGS)),
            Rule.any(List.of("boots", "shoes"), null,
                    EquipmentSlotMapper.baseItemsFor(EquipmentSlotMapper.Slot.FEET)),
            Rule.any(List.of("elytra", "wings"), null, List.of("minecraft:elytra"))
    );

    private static final List<String> GENERIC_FALLBACK = List.of(
            "minecraft:paper", "minecraft:stick", "minecraft:name_tag", "minecraft:compass",
            "minecraft:wooden_sword", "minecraft:stone_sword", "minecraft:iron_sword",
            "minecraft:golden_sword", "minecraft:diamond_sword", "minecraft:netherite_sword"
    );

    public static Optional<List<String>> match(String filenameStem) {
        if (filenameStem == null) return Optional.empty();
        String lower = filenameStem.toLowerCase();
        for (Rule r : WEAPON_RULES) {
            if (r.matches(lower)) return Optional.of(r.bases());
        }
        for (Rule r : ARMOR_RULES) {
            if (r.matches(lower)) return Optional.of(r.bases());
        }
        return Optional.empty();
    }

    public static List<String> genericFallback() {
        return GENERIC_FALLBACK;
    }

    private static List<String> woodenToNetherite(String tool) {
        return List.of(
                "minecraft:wooden_" + tool, "minecraft:stone_" + tool, "minecraft:iron_" + tool,
                "minecraft:golden_" + tool, "minecraft:diamond_" + tool, "minecraft:netherite_" + tool
        );
    }

    private static final class Rule {
        private final List<String> patterns;
        private final String negativeGuard;
        private final List<String> bases;
        private final java.util.function.Predicate<String> customMatch;

        private Rule(List<String> patterns, String negativeGuard, List<String> bases,
                     java.util.function.Predicate<String> customMatch) {
            this.patterns = patterns;
            this.negativeGuard = negativeGuard;
            this.bases = bases;
            this.customMatch = customMatch;
        }

        static Rule simple(String pattern, String negativeGuard, List<String> bases) {
            return new Rule(List.of(pattern), negativeGuard, bases, null);
        }

        static Rule any(List<String> patterns, String negativeGuard, List<String> bases) {
            return new Rule(patterns, negativeGuard, bases, null);
        }

        /**
         * Special-case bow rule: matches "bow" but rejects "elbow", "rainbow", "crossbow"
         * (crossbow handled by its own earlier rule but we still guard).
         */
        static Rule bow() {
            return new Rule(
                    List.of("bow"), null,
                    List.of("minecraft:bow", "minecraft:crossbow"),
                    s -> s.contains("bow")
                            && !s.contains("elbow")
                            && !s.contains("rainbow")
                            && !s.contains("crossbow"));
        }

        boolean matches(String lower) {
            if (customMatch != null) return customMatch.test(lower);
            if (negativeGuard != null && lower.contains(negativeGuard)) return false;
            for (String pat : patterns) {
                if (lower.contains(pat)) return true;
            }
            return false;
        }

        List<String> bases() { return bases; }
    }
}
