package com.magmaguy.resourcepackmanager.thirdparty;

import java.io.File;

public class ModelEngine extends ThirdPartyResourcePack {
    public ModelEngine() {
        super("ModelEngine",
                "ModelEngine" + File.separatorChar + "resource pack.zip",
                false,
                false,
                "meg reload");
    }
}