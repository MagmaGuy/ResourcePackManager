package com.magmaguy.resourcepackmanager.geyserbridge;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.event.bedrock.SessionDisconnectEvent;
import org.geysermc.geyser.api.event.bedrock.SessionLoginEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomBlocksEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPreInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserShutdownEvent;
import org.geysermc.geyser.api.extension.Extension;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Entry point Geyser loads for the RSPM custom Bedrock entity bridge.
 * <p>
 * Geyser's event bus reflects over every declared method of this class when the
 * extension is enabled ({@code getDeclaredMethods()}), which resolves the classes in
 * every method signature. If any signature referenced a class a given Geyser build
 * does not ship, that resolution would throw {@link NoClassDefFoundError} inside
 * Geyser's own initialization and take all of Geyser down with it — not just this
 * bridge. That includes Geyser internals AND newer API classes: the custom-entity
 * API this bridge uses only exists on Geyser 2.11+.
 * <p>
 * So this class must stay a pure shell referencing only API classes that exist on
 * every Geyser build we might be dropped into: no imports of
 * {@code org.geysermc.geyser.*} outside long-standing {@code org.geysermc.geyser.api.*}
 * classes, nothing from mcprotocollib, and no 2.11+ API types. All real logic lives
 * in {@link RspmGeyserBridgeCore}, which is only constructed after
 * {@link #missingRequiredClass()} confirms the running Geyser build ships everything
 * we compile against; 2.11+ events are subscribed dynamically from the core for the
 * same reason. If the probe fails, the bridge disables itself with a warning and
 * Geyser keeps running without custom entities.
 */
public class RspmGeyserBridgeExtension implements Extension {
    private static final String PROGRAMMATIC_ROUTE = "programmatic";
    private static final String ANNOTATED_ROUTE = "annotated-or-relayed";
    private static final String GEYSER_RECIPE_UTIL =
            "org.geysermc.geyser.inventory.recipe.RecipeUtil";

    /**
     * Classes {@link RspmGeyserBridgeCore} references that are not guaranteed on
     * every Geyser build: internals, mcprotocollib, and 2.11+ API additions. Probed
     * before the bridge activates; keep in sync with the core's imports.
     */
    private static final String[] REQUIRED_CLASSES = {
            // Geyser internals
            "org.geysermc.geyser.entity.BedrockEntityDefinition",
            "org.geysermc.geyser.entity.CustomBedrockEntityDefinition",
            "org.geysermc.geyser.entity.properties.GeyserEntityProperties",
            "org.geysermc.geyser.entity.type.Entity",
            GEYSER_RECIPE_UTIL,
            "org.geysermc.geyser.session.GeyserSession",
            "org.geysermc.geyser.util.InventoryUtils",
            // API surfaces introduced with Geyser 2.11's custom entity support
            "org.geysermc.geyser.api.entity.custom.CustomEntityDefinition",
            "org.geysermc.geyser.api.entity.data.GeyserEntityDataTypes",
            "org.geysermc.geyser.api.entity.property.GeyserEntityProperty",
            "org.geysermc.geyser.api.entity.property.type.GeyserBooleanEntityProperty",
            "org.geysermc.geyser.api.entity.property.type.GeyserFloatEntityProperty",
            "org.geysermc.geyser.api.entity.property.type.GeyserIntEntityProperty",
            "org.geysermc.geyser.api.entity.type.GeyserEntity",
            "org.geysermc.geyser.api.event.java.ServerSpawnEntityEvent",
            "org.geysermc.geyser.api.event.lifecycle.GeyserDefineEntitiesEvent",
            "org.geysermc.geyser.api.event.lifecycle.GeyserDefineEntityPropertiesEvent",
            // mcprotocollib (ships inside the Geyser jar)
            "org.geysermc.mcprotocollib.network.event.session.SessionAdapter",
            "org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundCustomPayloadPacket",
            "org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundCustomPayloadPacket"
    };

    /** Jar path of every class the bridge ships, including its shaded bridge-common and Gson. */
    private static final String BRIDGE_CLASS_PATH_PREFIX = "com/magmaguy/resourcepackmanager/geyserbridge/";

    static {
        defineBridgeClasses();
    }

    private volatile RspmGeyserBridgeCore core;
    private volatile boolean disabled;
    private volatile String activationRoute = "none";
    private volatile String programmaticRegistrationFailure;

    public RspmGeyserBridgeExtension() {
        registerProgrammaticLifecycle();
    }

    /**
     * Registers without Geyser's annotation scanner. Geyser constructs the extension
     * before attaching its scoped {@link #eventBus()}, but the global API bus is
     * already available at that point. Registering here therefore catches
     * pre-initialize and the registry-definition events even on bootstrap shapes
     * where reflected {@link Subscribe} discovery silently registers nothing.
     */
    private void registerProgrammaticLifecycle() {
        try {
            GeyserApi.api().eventBus().subscribe(this, GeyserPreInitializeEvent.class,
                    event -> handlePreInitialize(PROGRAMMATIC_ROUTE));
            GeyserApi.api().eventBus().subscribe(this, GeyserDefineCustomBlocksEvent.class,
                    event -> handleDefineCustomBlocks(PROGRAMMATIC_ROUTE));
            GeyserApi.api().eventBus().subscribe(this, GeyserPostInitializeEvent.class,
                    event -> handlePostInitialize(PROGRAMMATIC_ROUTE));
            GeyserApi.api().eventBus().subscribe(this, SessionLoginEvent.class,
                    event -> handleSessionJoin(event, PROGRAMMATIC_ROUTE));
            GeyserApi.api().eventBus().subscribe(this, SessionDisconnectEvent.class,
                    event -> handleSessionQuit(event, PROGRAMMATIC_ROUTE));
            GeyserApi.api().eventBus().subscribe(this, GeyserShutdownEvent.class,
                    event -> shutdown());
        } catch (Throwable throwable) {
            programmaticRegistrationFailure = throwable.toString();
            try {
                GeyserApi.api().eventBus().unregisterAll(this);
            } catch (Throwable ignored) {
                // Geyser's normal owner cleanup remains the final fallback.
            }
            // The extension container/logger is not attached until after this
            // constructor returns, so stderr is the only reliable early signal.
            System.err.println("[ResourcePackManagerGeyserBridge] SEVERE: could not install the early "
                    + "programmatic lifecycle registrar: " + throwable);
        }
    }

    @Subscribe
    public void onPreInitialize(GeyserPreInitializeEvent event) {
        handlePreInitialize(ANNOTATED_ROUTE);
    }

    private void handlePreInitialize(String route) {
        // Activate as early as possible so the core's dynamic event subscriptions
        // are in place before Geyser fires its entity-definition events.
        activeCore(route);
    }

    @Subscribe
    public void onDefineCustomBlocks(GeyserDefineCustomBlocksEvent event) {
        handleDefineCustomBlocks(ANNOTATED_ROUTE);
    }

    private void handleDefineCustomBlocks(String route) {
        // Velocity initializes its RSPM proxy plugin after Geyser's constructor
        // has fired PreInitialize. Its programmatic relay therefore uses this
        // long-standing event as the first remaining point before
        // GeyserDefineEntitiesEvent. Native extension delivery also enters here.
        activeCore(route);
    }

    @Subscribe
    public void onPostInitialize(GeyserPostInitializeEvent event) {
        handlePostInitialize(ANNOTATED_ROUTE);
    }

    private void handlePostInitialize(String route) {
        RspmGeyserBridgeCore activeCore = activeCore(route);
        if (activeCore != null) {
            guarded(activeCore::onPostInitialize);
        }
    }

    @Subscribe
    public void onSessionJoin(SessionLoginEvent event) {
        handleSessionJoin(event, ANNOTATED_ROUTE);
    }

    private void handleSessionJoin(SessionLoginEvent event, String route) {
        RspmGeyserBridgeCore activeCore = activeCore(route);
        if (activeCore != null) {
            guarded(() -> activeCore.onSessionJoin(event.connection()));
        }
    }

    @Subscribe
    public void onSessionQuit(SessionDisconnectEvent event) {
        handleSessionQuit(event, ANNOTATED_ROUTE);
    }

    private void handleSessionQuit(SessionDisconnectEvent event, String route) {
        RspmGeyserBridgeCore activeCore = activeCore(route);
        if (activeCore != null) {
            guarded(() -> activeCore.onSessionQuit(event.connection()));
        }
    }

    @Subscribe
    public void onShutdown(GeyserShutdownEvent event) {
        shutdown();
    }

    /** Also called directly by the Velocity plugin before it unregisters its relay. */
    public synchronized void shutdown() {
        RspmGeyserBridgeCore activeCore = core;
        core = null;
        if (activeCore != null) {
            guarded(activeCore::shutdown);
        }
    }

    private synchronized RspmGeyserBridgeCore activeCore(String route) {
        if ("none".equals(activationRoute)) {
            activationRoute = route;
        }
        if (disabled) {
            return null;
        }
        if (core != null) {
            return core;
        }

        String missingClass = missingRequiredClass();
        if (missingClass != null) {
            disabled = true;
            logger().warning("Disabled the RSPM custom Bedrock entity bridge: this Geyser build does not ship "
                    + missingClass + ", which the bridge was compiled against. Geyser itself is unaffected, but "
                    + "custom Bedrock entities will not appear until a ResourcePackManager update matches this "
                    + "Geyser version.");
            return null;
        }

        if (programmaticRegistrationFailure != null) {
            logger().severe("The RSPM early programmatic Geyser lifecycle registrar failed ("
                    + programmaticRegistrationFailure + "). The " + route
                    + " route activated the bridge, but this installation is not lifecycle-redundant.");
            programmaticRegistrationFailure = null;
        }

        try {
            primeGeyserRecipeNetworkIds();
        } catch (ClassNotFoundException | LinkageError error) {
            disabled = true;
            logger().warning("Disabled the RSPM custom Bedrock entity bridge: Geyser's built-in recipe "
                    + "network IDs could not be initialized before the first Bedrock session (" + error
                    + "). Geyser itself is unaffected, but accepting a Bedrock session now could publish "
                    + "colliding recipe IDs.");
            return null;
        }

        RspmGeyserBridgeCore createdCore = new RspmGeyserBridgeCore(this);
        core = createdCore;
        guarded(createdCore::subscribeVersionedEvents);
        return disabled ? null : core;
    }

    void logStartupHealth(int loadedDefinitionCount, String packUuid, boolean definitionWindowsObserved) {
        String version;
        try {
            version = description().version();
        } catch (Throwable ignored) {
            version = "unknown";
        }
        logger().info("RSPM Geyser bridge health: version=" + version
                + " artifactSha256=" + artifactSha256()
                + " activationRoute=" + activationRoute
                + " loadedDefinitions=" + loadedDefinitionCount
                + " packUuid=" + packUuid
                + " definitionWindows=" + (definitionWindowsObserved ? "observed" : "MISSED"));
    }

    private String artifactSha256() {
        try {
            URI location = RspmGeyserBridgeExtension.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI();
            Path artifact = Path.of(location);
            if (!Files.isRegularFile(artifact)) {
                return "unavailable";
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(artifact)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception | LinkageError ignored) {
            return "unavailable";
        }
    }

    /**
     * Defines every bridge class through this class's own loader while Geyser is still
     * loading extensions, so the whole bridge lives in one class loader.
     *
     * <p>On BungeeCord, Geyser loads extensions before ResourcePackManager's plugin class
     * loader exists, so this class and {@link RspmGeyserBridgeCore} are defined by Geyser's
     * extension loader. That loader asks its parent first, and BungeeCord's plugin loaders
     * search every other plugin, including the universal ResourcePackManager jar, which ships
     * these same classes. A bridge class first needed after ResourcePackManager loaded
     * ({@link ControllerPropertyIndex}, the core's packet listener and switch map, the shaded
     * bridge-common and Gson) was therefore defined by ResourcePackManager's loader instead.
     * Different loaders make different runtime packages, so the core's package-private access
     * failed with {@link IllegalAccessError} and the bridge disabled itself.</p>
     *
     * <p>Resolution only ever gains loaders over time, so loading here can only keep a class in
     * this loader or resolve it as it would have been resolved later. Where the bridge already
     * resolved to one loader, as on Velocity, this only moves the loading earlier. Classes are
     * not initialized, and a class whose Geyser supertype this build lacks is skipped:
     * {@link #missingRequiredClass()} disables the bridge before anything uses it.</p>
     */
    private static void defineBridgeClasses() {
        try {
            ClassLoader loader = RspmGeyserBridgeExtension.class.getClassLoader();
            Path artifact = Path.of(RspmGeyserBridgeExtension.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            if (!Files.isRegularFile(artifact)) {
                // Exploded classes (unit tests) have no second copy to split against.
                return;
            }
            try (ZipFile jar = new ZipFile(artifact.toFile())) {
                Enumeration<? extends ZipEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (!name.startsWith(BRIDGE_CLASS_PATH_PREFIX) || !name.endsWith(".class")
                            || name.endsWith("module-info.class") || name.endsWith("package-info.class")) {
                        continue;
                    }
                    String className = name.substring(0, name.length() - ".class".length()).replace('/', '.');
                    try {
                        Class.forName(className, false, loader);
                    } catch (ClassNotFoundException | LinkageError skipped) {
                        // Left to load lazily, as before; see the method comment.
                    }
                }
            }
        } catch (Exception | LinkageError ignored) {
            // Best effort: a failure here leaves the earlier lazy loading, and must never
            // stop Geyser from loading the extension.
        }
    }

    /**
     * Forces Geyser's four built-in cartography recipes to reserve their global
     * network IDs before any {@code GeyserSession} snapshots the next ID.
     *
     * <p>Geyser 2.11 initializes {@code RecipeUtil.CARTOGRAPHY_RECIPES} lazily,
     * incrementing {@code InventoryUtils.LAST_RECIPE_NET_ID} from zero to four.
     * A session created first captures one as its next ID; when RecipeUtil is
     * initialized later, smithing and stonecutter recipes reuse IDs already held
     * by MapExtendingRecipe and MapCloningRecipe. Pre-initialize is the last
     * deterministic point before a Bedrock session can be constructed.
     */
    private void primeGeyserRecipeNetworkIds() throws ClassNotFoundException {
        Class.forName(
                GEYSER_RECIPE_UTIL,
                true,
                RspmGeyserBridgeExtension.class.getClassLoader()
        );
    }

    private String missingRequiredClass() {
        for (String className : REQUIRED_CLASSES) {
            try {
                Class.forName(className, false, RspmGeyserBridgeExtension.class.getClassLoader());
            } catch (ClassNotFoundException | LinkageError e) {
                return className;
            }
        }
        return null;
    }

    /**
     * Runs bridge logic, disabling the bridge instead of propagating if the running
     * Geyser build turns out to be binary-incompatible in a way the class probe could
     * not detect (removed or re-signatured methods, changed constructors, etc.).
     * Every entry point into {@link RspmGeyserBridgeCore} — subscribed events,
     * scheduler tasks, packet listeners — must route through this.
     */
    void guarded(Runnable action) {
        if (disabled) {
            return;
        }
        try {
            action.run();
        } catch (LinkageError error) {
            disableAfterLinkageError(error);
        }
    }

    private void disableAfterLinkageError(LinkageError error) {
        RspmGeyserBridgeCore activeCore;
        synchronized (this) {
            if (disabled) {
                return;
            }
            disabled = true;
            activeCore = core;
            core = null;
        }
        // Linkage failure can originate from an event, packet listener, or one
        // of the core's scheduler tasks. Stop the already-created core here so
        // binary incompatibility cannot leave retry threads and session state
        // alive for the rest of the proxy process.
        if (activeCore != null) {
            try {
                activeCore.shutdown();
            } catch (Throwable ignored) {
                // Compatibility shutdown is best-effort; Geyser itself must stay up.
            }
        }
        logger().warning("Disabled the RSPM custom Bedrock entity bridge: this Geyser build is binary-"
                + "incompatible with the version the bridge was compiled against (" + error + "). Geyser "
                + "itself is unaffected, but custom Bedrock entities will not appear until a "
                + "ResourcePackManager update matches this Geyser version.");
    }
}
