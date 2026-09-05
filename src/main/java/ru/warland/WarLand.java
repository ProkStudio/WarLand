package ru.warland;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.warland.core.CoreRuntime;
import ru.warland.moderation.Moderation;

public final class WarLand implements ModInitializer {
    public static final String ID="warland";
    public static final Logger LOG=LoggerFactory.getLogger(ID);
    @Override public void onInitialize(){try{CoreRuntime runtime=new CoreRuntime();runtime.initialize();Moderation.register(runtime);}catch(Exception e){throw new IllegalStateException("WarLand failed closed during initialization",e);}}
}
