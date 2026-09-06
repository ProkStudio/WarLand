package ru.warland.auth;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RuntimePolicyTest {
 @Test void requiresBothOnlineIdentityAndEncryption() {
  assertTrue(RuntimePolicy.secure(true,true));
  assertFalse(RuntimePolicy.secure(true,false));assertFalse(RuntimePolicy.secure(false,true));assertFalse(RuntimePolicy.secure(false,false));
 }
 @Test void credentialsNeverReachCommandDispatcher() {
  for(String input:new String[]{"login secret", "REGISTER secret secret", "/reg secret", "l secret", "warland:login secret", "minecraft:register secret", "execute as @s run warland:login secret", "changepassword secret", "  /LOGIN secret"})assertTrue(RuntimePolicy.credentialCommand(input),input);
  assertFalse(RuntimePolicy.credentialCommand("balance"));assertFalse(RuntimePolicy.credentialCommand("warland tutorial"));
  assertTrue(RuntimePolicy.credentialCommand("x".repeat(257)));assertTrue(RuntimePolicy.credentialCommand(null));
 }
 @Test void onlyRealConsoleOutputWithoutEntity() {
  Object server=new Object();assertTrue(RuntimePolicy.console(server,server,false));
  assertFalse(RuntimePolicy.console(new Object(),server,false)); // RCON / command block / wrapped output
  assertFalse(RuntimePolicy.console(server,server,true));assertFalse(RuntimePolicy.console(null,null,false));
 }
}
