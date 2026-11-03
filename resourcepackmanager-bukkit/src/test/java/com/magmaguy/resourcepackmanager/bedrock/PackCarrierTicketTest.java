package com.magmaguy.resourcepackmanager.bedrock;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.magmaguy.resourcepackmanager.mixer.engine.MixerLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Temporary check for the Discord ticket "RSPM maps 3d models": ModelEngine bones mapped
 * under about twelve guessed base items, and a network with ~200 ModelEngine mobs reached
 * 31,970 Geyser custom items. ModelEngine names each bone twice: a custom-model-data branch
 * in {@code minecraft:leather_horse_armor} and a {@code modelengine:<model>/<bone>} item
 * definition. A ModelEngine-shaped pack goes through {@link BedrockConversion}.
 */
@EnabledIfEnvironmentVariable(named = "NIGHTBREAK_TEMP_TICKET_TESTS", matches = "true")
final class PackCarrierTicketTest {

    private static final String HORSE_ARMOR = "minecraft:leather_horse_armor";
    private static final List<String> BONES = List.of("modelengine:ghoul/head", "modelengine:ghoul/sword_r");

    @TempDir
    Path temp;

    @Test
    void bonesMapOnlyUnderTheHorseArmorTheirPackPutsThemOn() throws IOException {
        List<Mapping> mappings = convert(true);
        Map<String, Set<String>> bases = basesByModel(mappings);

        for (String bone : BONES)
            assertEquals(Set.of(HORSE_ARMOR), bases.get(bone), bone + " bases");

        // The custom-model-data branches still map, one per bone, so each bone has two items.
        List<Mapping> cmd = mappings.stream().filter(m -> m.model().equals(HORSE_ARMOR)).toList();
        assertEquals(2, cmd.size(), "custom-model-data definitions: " + cmd);
        assertTrue(cmd.stream().allMatch(m -> m.base().equals(HORSE_ARMOR)), "carrier of " + cmd);
        assertEquals(4, mappings.stream().filter(m -> BONES.contains(m.model()) || m.model().equals(HORSE_ARMOR)).count(),
                "Bedrock items for the two bones");

        // An item no vanilla definition carries keeps every guessed base.
        assertEquals(new TreeSet<>(BedrockMappingFixture.GUESSED_BASES), bases.get("custom:ruby"));
        // A definition the pack covers only in part keeps its guesses for the uncovered model.
        assertTrue(bases.get("modelengine:ghoul/mixed").containsAll(BedrockMappingFixture.GUESSED_BASES),
                "mixed bases: " + bases.get("modelengine:ghoul/mixed"));
        assertEquals(mappings.size(), new HashSet<>(mappings.stream().map(Mapping::bedrockIdentifier).toList()).size(),
                "bedrock identifiers are unique");
    }

    @Test
    void withoutTheHorseArmorDefinitionBonesKeepTheGuessedBases() throws IOException {
        Map<String, Set<String>> bases = basesByModel(convert(false));

        // The ticket's symptom: the generic fallback, or the six swords a "sword" name guesses.
        assertEquals(new TreeSet<>(BedrockMappingFixture.GUESSED_BASES), bases.get("modelengine:ghoul/head"));
        assertEquals(new TreeSet<>(BedrockMappingFixture.GUESSED_BASES.subList(4, 10)), bases.get("modelengine:ghoul/sword_r"));
    }

    private record Mapping(String base, String model, String bedrockIdentifier) {
    }

    private static Map<String, Set<String>> basesByModel(List<Mapping> mappings) {
        Map<String, Set<String>> bases = new TreeMap<>();
        for (Mapping mapping : mappings)
            bases.computeIfAbsent(mapping.model(), model -> new TreeSet<>()).add(mapping.base());
        return bases;
    }

    private List<Mapping> convert(boolean withHorseArmor) throws IOException {
        Path pack = Files.createTempDirectory(temp, "pack-");
        BedrockMappingFixture.write(pack.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":84,\"description\":\"me\"}}");
        if (withHorseArmor)
            BedrockMappingFixture.write(pack.resolve("assets/minecraft/items/leather_horse_armor.json"), """
                    {"model":{"type":"minecraft:range_dispatch","property":"minecraft:custom_model_data",
                      "entries":[
                        {"threshold":1,"model":{"type":"minecraft:model","model":"modelengine:ghoul/head"}},
                        {"threshold":2,"model":{"type":"minecraft:model","model":"modelengine:ghoul/sword_r"}}],
                      "fallback":{"type":"minecraft:model","model":"minecraft:item/leather_horse_armor"}}}
                    """);
        Path engine = pack.resolve("assets/modelengine");
        for (String bone : List.of("head", "sword_r", "mixed_part")) {
            BedrockMappingFixture.write(engine.resolve("models/ghoul/" + bone + ".json"), """
                    {"textures":{"0":"modelengine:entity/ghoul","particle":"modelengine:entity/ghoul"},
                     "elements":[{"from":[4,4,4],"to":[12,12,12],"faces":{
                       "north":{"uv":[0,0,8,8],"texture":"#0"},"south":{"uv":[0,0,8,8],"texture":"#0"},
                       "east":{"uv":[0,0,8,8],"texture":"#0"},"west":{"uv":[0,0,8,8],"texture":"#0"},
                       "up":{"uv":[0,0,8,8],"texture":"#0"},"down":{"uv":[0,0,8,8],"texture":"#0"}}}]}
                    """);
        }
        for (String bone : List.of("head", "sword_r"))
            BedrockMappingFixture.write(engine.resolve("items/ghoul/" + bone + ".json"),
                    "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"modelengine:ghoul/" + bone + "\"}}");
        // Half of this definition is a bone the horse armor carries, half is not.
        BedrockMappingFixture.write(engine.resolve("items/ghoul/mixed.json"), """
                {"model":{"type":"minecraft:condition","property":"minecraft:using_item",
                  "on_true":{"type":"minecraft:model","model":"modelengine:ghoul/head"},
                  "on_false":{"type":"minecraft:model","model":"modelengine:ghoul/mixed_part"}}}
                """);
        png(engine.resolve("textures/entity/ghoul.png"));
        BedrockMappingFixture.write(pack.resolve("assets/custom/items/ruby.json"),
                "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"custom:item/ruby\"}}");
        BedrockMappingFixture.write(pack.resolve("assets/custom/models/item/ruby.json"),
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"custom:item/ruby\"}}");
        png(pack.resolve("assets/custom/textures/item/ruby.png"));

        File out = Files.createTempDirectory(temp, "out-").toFile();
        MixerLogger silent = new MixerLogger() {
            @Override public void info(String message) { }
            @Override public void warn(String message) { }
            @Override public void collision(String message) { }
        };
        BedrockConverterContext context = new BedrockConverterContext() {
            @Override public MixerLogger logger() { return silent; }
            @Override public String pluginVersion() { return "ticket"; }
            @Override public boolean isBedrockTargetPresent() { return true; }
            @Override public boolean isBedrockConversionEnabled() { return true; }
            @Override public void deployMappingsIfNeeded(File mappingsFile) { }
            @Override public File previousMappingsFile() { return null; }
            @Override public Map<String, List<String>> declaredBaseItems() { return Map.of(); }
        };
        assertTrue(BedrockConversion.generate(pack.toFile(), out, context));

        JsonObject root = JsonParser.parseString(Files.readString(
                new File(out, BedrockConversion.GEYSER_MAPPINGS_NAME).toPath())).getAsJsonObject();
        List<Mapping> mappings = new ArrayList<>();
        for (Map.Entry<String, JsonElement> base : root.getAsJsonObject("items").entrySet())
            for (JsonElement definition : base.getValue().getAsJsonArray()) {
                JsonObject object = definition.getAsJsonObject();
                mappings.add(new Mapping(base.getKey(), object.get("model").getAsString(),
                        object.get("bedrock_identifier").getAsString()));
            }
        return mappings;
    }

    private static void png(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 16; x++)
            for (int y = 0; y < 16; y++)
                image.setRGB(x, y, 0xFFC86432);
        ImageIO.write(image, "png", file.toFile());
    }
}
