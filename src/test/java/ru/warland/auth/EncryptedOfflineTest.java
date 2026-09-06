package ru.warland.auth;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class EncryptedOfflineTest {
 @TempDir Path root;
 @Test void identitySurvivesRestartAndPinMatches()throws Exception {
  var first=ServerIdentity.loadOrCreate(root.resolve("identity"));var next=ServerIdentity.loadOrCreate(root.resolve("identity"));
  assertArrayEquals(first.getPrivate().getEncoded(),next.getPrivate().getEncoded());assertArrayEquals(first.getPublic().getEncoded(),next.getPublic().getEncoded());
  Path pin=root.resolve("pin");Files.writeString(pin,ServerIdentity.fingerprint(first.getPublic())+"\n");assertTrue(ServerPin.matches(pin,next.getPublic()));
  var other=ServerIdentity.loadOrCreate(root.resolve("other"));assertFalse(ServerPin.matches(pin,other.getPublic()));
  assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(root.resolve("identity/identity.bin")));
  assertEquals(PosixFilePermissions.fromString("rwx------"),Files.getPosixFilePermissions(root.resolve("identity")));
 }
 @Test void corruptIdentityIsPreservedAndRefused()throws Exception {
  Path dir=root.resolve("identity");ServerIdentity.loadOrCreate(dir);Path file=dir.resolve("identity.bin");Files.writeString(file,"bad");
  assertThrows(Exception.class,()->ServerIdentity.loadOrCreate(dir));assertEquals("bad",Files.readString(file));
 }
 @Test void unsafeModesAndLinksAreRefused()throws Exception {
  Path dir=root.resolve("identity");ServerIdentity.loadOrCreate(dir);Path file=dir.resolve("identity.bin");
  Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-r--r--"));assertThrows(Exception.class,()->ServerIdentity.loadOrCreate(dir));
  Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));Files.createLink(root.resolve("hardlink"),file);assertThrows(Exception.class,()->ServerIdentity.loadOrCreate(dir));
  Files.createSymbolicLink(root.resolve("link"),dir);assertThrows(Exception.class,()->ServerIdentity.loadOrCreate(root.resolve("link")));
 }
 @Test void absentInvalidOversizedAndLinkedPinAreRefused()throws Exception {
  var key=ServerIdentity.loadOrCreate(root.resolve("identity")).getPublic();Path pin=root.resolve("pin");assertFalse(ServerPin.matches(pin,key));
  for(String value:List.of("", "x", "f".repeat(64), "a".repeat(129), ServerIdentity.fingerprint(key)+"\nextra")){Files.writeString(pin,value);assertFalse(ServerPin.matches(pin,key));}
  Files.writeString(pin,ServerIdentity.fingerprint(key));Files.createSymbolicLink(root.resolve("linked-pin"),pin);assertFalse(ServerPin.matches(root.resolve("linked-pin"),key));
 }
 @Test void offlinePolicyDoesNotAllowPlaintextOrImplicitOptIn() {
  assertFalse(OfflinePolicy.secure(false,false,true,true));assertFalse(OfflinePolicy.secure(false,true,true,false));assertTrue(OfflinePolicy.secure(false,true,true,true));
  assertFalse(OfflinePolicy.secure(true,false,true,true));assertTrue(OfflinePolicy.secure(true,true,true,false));
  assertFalse(OfflinePolicy.secure(false,true,false,true));
 }
 @Test void startupOptInDefaultsOff()throws Exception {
  assertFalse(OfflinePolicy.enabled(false,true,false));assertFalse(OfflinePolicy.enabled(true,true,true));assertFalse(OfflinePolicy.enabled(false,false,true));assertTrue(OfflinePolicy.enabled(false,true,true));
 }
}
