package com.magmaguy.resourcepackmanager.thirdparty;

import java.io.File;

public class ItemsAdder extends ThirdPartyResourcePack {
    public ItemsAdder() {
        super("ItemsAdder",
                "ItemsAdder" + File.separatorChar + "output" + File.separatorChar + "generated.zip",
                false,
                false,
                "");
    }
}
