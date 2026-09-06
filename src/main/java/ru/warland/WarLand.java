package ru.warland;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.warland.auth.AuthPayloads;
import ru.warland.combat.CombatFeature;
import ru.warland.core.CoreRuntime;
import ru.warland.core.SurvivalTransit;
import ru.warland.moderation.Moderation;
import ru.warland.rtp.RtpService;

public final class WarLand implements ModInitializer {
    public static final String ID="warland";
    public static final Logger LOG=LoggerFactory.getLogger(ID);
    @Override public void onInitialize(){
        AuthPayloads.register();
        CombatFeature.registerItems();
        if(FabricLoader.getInstance().getEnvironmentType()==EnvType.CLIENT)return;
        try{CoreRuntime runtime=new CoreRuntime();runtime.initialize();Moderation.register(runtime);SurvivalTransit.register(runtime);RtpService.register(runtime);}
        catch(Exception e){throw new IllegalStateException("WarLand failed closed during initialization",e);}
    }
}
