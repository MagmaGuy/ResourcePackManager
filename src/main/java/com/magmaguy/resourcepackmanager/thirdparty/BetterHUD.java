package com.magmaguy.resourcepackmanager.thirdparty;

import java.io.File;

public class BetterHUD extends ThirdPartyResourcePack {
    public BetterHUD() {
        super("BetterHUD",
                "BetterHud" + File.separatorChar + "build.zip",
                true,
                false,
                true,
                true,
                "betterhud reload");
    }
}
