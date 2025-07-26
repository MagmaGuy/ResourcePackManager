package com.magmaguy.resourcepackmanager.config.compatibleplugins;

import com.magmaguy.magmacore.config.CustomConfigFields;
import com.magmaguy.resourcepackmanager.thirdparty.ThirdPartyResourcePack;
import lombok.Getter;
import lombok.Setter;

public class CompatiblePluginConfigFields extends CustomConfigFields {

    @Getter
    @Setter
    private String pluginName = "placeholder";
    @Getter
    @Setter
    private String url = null;
    @Getter
    @Setter
    private String localPath = null;
    @Getter
    @Setter
    private boolean encrypts = false;
    @Getter
    @Setter
    private boolean distributes = false;
    @Getter
    @Setter
    private boolean zips = true;
    @Getter
    @Setter
    private String reloadCommand;
    @Getter
    @Setter
    private boolean resourcePackUpdated = false;
    @Getter
    @Setter
    private String mixerFilename;

    public CompatiblePluginConfigFields(String filename, boolean isEnabled) {
        super(filename, isEnabled);
    }

    @Override
    public void processConfigFields() {
        this.isEnabled = processBoolean("isEnabled", isEnabled, isEnabled, true);
        this.pluginName = processString("pluginName", pluginName, pluginName, true);
        this.url = processString("url", url, url, true);
        this.encrypts = processBoolean("encrypts", encrypts, encrypts, true);
        this.distributes = processBoolean("distributes", distributes, distributes, true);
        this.zips = processBoolean("zips", zips, zips, true);
        this.reloadCommand = processString("reloadCommand", reloadCommand, reloadCommand, true);
        this.resourcePackUpdated = processBoolean("resourcePackUpdated", resourcePackUpdated, resourcePackUpdated, true);
        this.mixerFilename = processString("mixerFilename", mixerFilename, mixerFilename, true);
        this.localPath = processString("localPath", localPath, localPath, true);

        if (isEnabled) ThirdPartyResourcePack.initializeThirdPartyResourcePack(this);
    }
}
