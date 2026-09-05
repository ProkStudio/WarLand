package ru.warland;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WarLand implements ModInitializer {
    public static final String ID = "warland";
    public static final Logger LOG = LoggerFactory.getLogger(ID);
    @Override public void onInitialize() { LOG.info("WarLand bootstrap: Fabric 1.21.11"); }
}
