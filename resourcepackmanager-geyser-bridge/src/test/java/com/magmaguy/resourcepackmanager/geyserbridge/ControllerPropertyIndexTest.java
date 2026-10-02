package com.magmaguy.resourcepackmanager.geyserbridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ControllerPropertyIndexTest {
    private static JsonObject description(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void findsPropertiesOfControllersInRenamedFiles() {
        ControllerPropertyIndex index = new ControllerPropertyIndex();
        // The importer stored this controller under a hashed file name such as bb9af3c2.animation_controllers.json.
        index.addControllerFile("""
                {"format_version": "1.10.0", "animation_controllers": {
                  "controller.animation.fmm.a_very_long_model_identifier_here.a_69646c65": {"states": {
                    "stop": {"transitions": [{"play": "math.mod(math.floor(query.property('freeminecraftmodels:anim0') / 1), 2) == 1"}]},
                    "play": {"animations": ["idle"]}}}}}""");
        index.addControllerFile("""
                {"format_version": "1.10.0", "animation_controllers": {
                  "controller.animation.fmm.other.a_00": {"states": {"default": {"transitions": [
                    {"play": "query.property('freeminecraftmodels:other') == 1"}]}}}}}""");

        Set<String> properties = index.propertiesFor(description("""
                {"identifier": "fmm:a_very_long_model_identifier_here",
                 "animations": {"idle": "animation.fmm.a_very_long_model_identifier_here.idle",
                                "idle_controller": "controller.animation.fmm.a_very_long_model_identifier_here.a_69646c65"}}"""));
        assertEquals(Set.of("freeminecraftmodels:anim0"), properties, "Only the referenced controller's properties");
    }

    @Test
    void readsRenderAndLegacyAnimationControllerReferences() {
        ControllerPropertyIndex index = new ControllerPropertyIndex();
        index.addControllerFile("""
                {"render_controllers": {"controller.render.fmm_x": {"part_visibility": [{"*": "query.property(\\"fmm:visible\\") == 1"}]}}}""");
        index.addControllerFile("""
                {"animation_controllers": {"controller.animation.x": {"states": {"default": {"transitions": [{"a": "query.property('fmm:state') > 0"}]}}}}}""");

        Set<String> properties = index.propertiesFor(description("""
                {"render_controllers": [{"controller.render.fmm_x": "query.is_baby"}],
                 "animation_controllers": [{"main": "controller.animation.x"}]}"""));
        assertEquals(Set.of("fmm:visible", "fmm:state"), properties);
    }
}
