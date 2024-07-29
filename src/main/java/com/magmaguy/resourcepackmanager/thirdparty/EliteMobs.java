package com.magmaguy.resourcepackmanager.thirdparty;

import lombok.Getter;

import java.io.File;

public class EliteMobs extends ThirdPartyResourcePack {
    public EliteMobs() {
        super("EliteMobs",
                "EliteMobs" + File.separatorChar + "exports" + File.separatorChar + "elitemobs_resource_pack.zip",
                false,
                false,
                true,
                true,
                "elitemobs reload");
    }
}
