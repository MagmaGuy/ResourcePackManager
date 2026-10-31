package com.magmaguy.resourcepackmanager.craftengine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With CraftEngine's default config, players got CraftEngine's own pack on join and RSPM's
 * merged pack that already contains it (RSPM#20). RSPM reports that from CraftEngine's
 * config.yml and leaves the file alone.
 */
final class CraftEngineDeliveryTest {

    @TempDir
    Path temp;

    @Test
    void craftEngineSendsOnJoinUnlessItsConfigTurnsItOff() throws IOException {
        assertTrue(CraftEngineDelivery.sendsPackOnJoin(config(delivery("true"))));
        assertFalse(CraftEngineDelivery.sendsPackOnJoin(config(delivery("false"))));
    }

    @Test
    void anAbsentSettingMeansCraftEnginesDefault() throws IOException {
        assertTrue(CraftEngineDelivery.sendsPackOnJoin(temp.resolve("missing/config.yml").toFile()),
                "CraftEngine writes send-on-join: true when it creates its config");
        assertTrue(CraftEngineDelivery.sendsPackOnJoin(config("""
                resource-pack:
                  delivery:
                    prompt: "<yellow>Accept our pack</yellow>"
                """)));
    }

    @Test
    void anUnreadableConfigIsNotReportedAsSendingOnJoin() throws IOException {
        assertFalse(CraftEngineDelivery.sendsPackOnJoin(config("resource-pack: [unterminated\n")));
    }

    @Test
    void doubleDeliveryNeedsRspmToSendAndToMergeCraftEngine() throws IOException {
        File sendsOnJoin = config(delivery("true"));

        assertTrue(CraftEngineDelivery.doubleDelivery(true, true, sendsOnJoin));
        assertFalse(CraftEngineDelivery.doubleDelivery(false, true, sendsOnJoin),
                "Without RSPM hosting, CraftEngine's pack is the only one players get");
        assertFalse(CraftEngineDelivery.doubleDelivery(true, false, sendsOnJoin),
                "With craft_engine.yml disabled, RSPM's pack does not contain CraftEngine's");
        assertFalse(CraftEngineDelivery.doubleDelivery(true, true, config(delivery("false"))));
    }

    private static String delivery(String sendOnJoin) {
        return """
                resource-pack:
                  self-host:
                    rate_limiting:
                      max_bandwidth_per_second: 5_000_000
                      qps_per_ip: 10/60
                  delivery:
                    prompt: "<yellow>Accept our pack</yellow>"
                    send-on-join: %s
                    kick-if-declined: true
                """.formatted(sendOnJoin);
    }

    private File config(String content) throws IOException {
        Path file = Files.createTempFile(temp, "config", ".yml");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file.toFile();
    }
}
