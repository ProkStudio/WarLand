package ru.warland.content;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** Source-level integration contracts only; these are not a Minecraft runtime smoke test. */
class BuildingAuthorizationWiringTest {
    private String source() throws Exception {
        return Files.readString(Path.of("src/main/java/ru/warland/content/BuildingService.java"));
    }
    private String method(String start,String end) throws Exception {
        String s=source();return s.substring(s.indexOf(start),s.indexOf(end,s.indexOf(start)));
    }
    @Test void noWildernessPermissionFallbackRemains() throws Exception {
        String s=source();
        assertFalse(s.contains("api.canBuild("));
        assertTrue(s.contains("api.canBuildCity(player,world,pos)"));
        assertTrue(s.contains("api.canBuildCity(player,world,below)"));
        assertTrue(s.contains("!api.inventoryLocked(p.getUuid())"));
    }
    @Test void fullSiteIsRecheckedAfterBalanceBeforeReservation() throws Exception {
        String s=method("private void validate(","private void reject(");
        int callback=s.indexOf("api.balance(p.owner).whenComplete");
        int gate=s.indexOf("!authorizedSite(current,p.plan,p.origin)",callback);
        assertTrue(callback>=0 && gate>callback && gate<s.indexOf("ContentData.Job job=new"));
    }
    @Test void initialPaymentRechecksButUncertainPaymentStillResolvesSameId() throws Exception {
        String s=method("private void charge(","private void takeMaterials(");
        assertTrue(s.contains("job.stage.equals(\"CHARGING\") &&"));
        int gate=s.indexOf("!authorizedJob(");
        assertTrue(gate>0 && gate<s.indexOf("api.debit("));
        assertTrue(s.contains("job.operationId()"));
        assertTrue(s.contains("Set.of(\"CHARGING\",\"PAYMENT_REVIEW\")"));
    }
    @Test void materialRemovalRechecksAfterDurableIntent() throws Exception {
        String s=method("private void takeMaterials(","private void beginBatch(");
        assertTrue(s.contains("!authorizedJob(player,job)"));
        int intent=s.indexOf("j.stage=\"MATERIAL_INTENT\"");
        int gate=s.indexOf("!authorizedJob(current,store.data().jobs.get(id))",intent);
        assertTrue(intent>0 && gate>intent && gate<s.indexOf("removeMaterials(current,plan)"));
        assertTrue(s.substring(gate).contains("stage=\"WAITING_MATERIALS\""));
    }
    @Test void placementChecksWholeSiteAndEachCellAfterJournal() throws Exception {
        String s=method("private void beginBatch(","private void finish(");
        int journal=s.indexOf("j.stage=\"BATCH\"");
        int gate=s.indexOf("!authorizedJob(player,latest)");
        assertTrue(journal>0 && gate>journal && gate<s.indexOf("else while("));
        int cell=s.indexOf("api.canBuildCity(player,world,pos)");
        assertTrue(cell>gate && cell<s.indexOf("world.setBlockState("));
    }
}
