package com.magmaguy.resourcepackmanager.thirdparty;

import java.io.File;

public class Nexo extends ThirdPartyResourcePack {
    public Nexo() {
        super("Nexo",
                "Nexo" + File.separatorChar + "pack" + File.separatorChar + "pack.zip",
                false,
                false,
                true,
                true,
                "n reload pack");
    }
}
