package ru.warland.core;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.UUID;
import ru.warland.WarLand;

/** Optional integration hook. Broken installed guards fail closed, absent modules do not. */
public final class InventoryLocks {
    private static boolean initialized,broken;
    private static Method check;
    private InventoryLocks() {}
    private static synchronized void initialize() {
        if(initialized)return;
        initialized=true;
        try { Class.forName("ru.warland.economy.MarketFeature",false,InventoryLocks.class.getClassLoader()); }
        catch(ClassNotFoundException absent) { return; }
        try {
            check=Class.forName("ru.warland.economy.InventoryGuard").getMethod("busy",UUID.class);
            if(!Modifier.isStatic(check.getModifiers())||check.getReturnType()!=boolean.class)throw new IllegalStateException("InventoryGuard.busy must be public static boolean");
        } catch(ReflectiveOperationException|LinkageError|RuntimeException e) { fail(e); }
    }
    private static void fail(Throwable e) {
        if(!broken)WarLand.LOG.error("Inventory guard failed; inventory-sensitive actions locked",e);
        broken=true;
    }
    public static boolean busy(UUID player) {
        initialize();
        if(broken)return true;
        if(check==null)return false;
        try { return (boolean)check.invoke(null,player); }
        catch(ReflectiveOperationException|RuntimeException e) { fail(e);return true; }
    }
}
