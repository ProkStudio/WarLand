package ru.warland.moderation;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModerationStateTest {
 static final String ID="00000000-0000-0000-0000-000000000001";
 @Test void absentDocumentStartsEmpty(){assertTrue(Moderation.decode(null).roles.isEmpty());}
 @Test void legacyUnversionedObjectIsAdoptedWithoutDroppingRoles(){
  var state=Moderation.decode("{\"roles\":{\""+ID+"\":\"MODERATOR\"},\"bans\":{},\"mutes\":{}}");
  assertEquals(Moderation.Role.MODERATOR,state.roles.get(ID));assertEquals(1,state.schemaVersion);
 }
 @Test void futureSchemaAndNullMapsAreRejected(){
  assertThrows(RuntimeException.class,()->Moderation.decode("{\"schemaVersion\":2}"));
  assertThrows(RuntimeException.class,()->Moderation.decode("{\"roles\":null}"));
  assertThrows(RuntimeException.class,()->Moderation.decode("{\"schemaVersion\":1.5}"));
  assertThrows(RuntimeException.class,()->Moderation.decode("{\"schemaVersion\":\"1\"}"));
 }
 @Test void blankMalformedAndNullDocumentsAreNotAnEmptyReset(){
  for(String json:new String[]{""," ","{","null","[]"})assertThrows(RuntimeException.class,()->Moderation.decode(json));
 }
 @Test void unknownRolesAndNoncanonicalIdsAreRejected(){
  assertThrows(RuntimeException.class,()->Moderation.decode("{\"roles\":{\""+ID+"\":\"OWNER\"}}"));
  assertThrows(RuntimeException.class,()->Moderation.decode("{\"roles\":{\"1-1-1-1-1\":\"MODERATOR\"}}"));
 }
 @Test void invalidSanctionsAreRejected(){
  var state=new Moderation.State();state.bans.put(ID,new Moderation.Sanction("mute","reason",0,"console"));
  assertThrows(RuntimeException.class,()->Moderation.validate(state));
  state.bans.put(ID,new Moderation.Sanction("ban","reason",-1,"console"));assertThrows(RuntimeException.class,()->Moderation.validate(state));
 }
 @Test void publishedMapsCannotBeMutated(){
  var state=Moderation.decode(null);assertThrows(UnsupportedOperationException.class,()->state.roles.put(ID,Moderation.Role.CURATOR));
 }
 @Test void roleLimitPreventsUnboundedState(){
  var state=new Moderation.State();for(int i=0;i<257;i++)state.roles.put(UUID.randomUUID().toString(),Moderation.Role.MODERATOR);
  assertThrows(RuntimeException.class,()->Moderation.validate(state));
 }
 @Test void textValidationRejectsControlsAndBlankReasons(){
  assertFalse(Moderation.validText("",300));assertFalse(Moderation.validText("bad\nreason",300));
  assertFalse(Moderation.validText("a".repeat(301),300));assertTrue(Moderation.validText("Проверенная причина",300));
 }
}
