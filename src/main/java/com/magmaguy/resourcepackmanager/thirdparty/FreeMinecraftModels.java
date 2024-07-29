package com.magmaguy.resourcepackmanager.thirdparty;

import java.io.File;

public class FreeMinecraftModels extends ThirdPartyResourcePack {
    public FreeMinecraftModels() {
        super("FreeMinecraftModels",
                "FreeMinecraftModels" + File.separatorChar + "output" + File.separatorChar + "FreeMinecraftModels.zip",
                false,
                false,
                true,
                true,
                "freeminecraftmodels reload");
    }
}
