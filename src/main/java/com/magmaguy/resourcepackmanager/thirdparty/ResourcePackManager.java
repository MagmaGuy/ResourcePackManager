package com.magmaguy.resourcepackmanager.thirdparty;

import java.io.File;

public class ResourcePackManager  extends ThirdPartyResourcePack {
    public ResourcePackManager() {
        super("ResourcePackManager",
                "ResourcePackManager" + File.separatorChar + "blueprint" + File.separatorChar + "blueprint.zip",
                false,
                false,
                true,
                true,
                "");
    }
}
