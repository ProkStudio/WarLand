package ru.warland.auth;
import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.util.*;
/** Public, pre-distributed pin. Never trust-on-first-use or learn a key from the network. */
public final class ServerPin {
 private ServerPin() {}
 public static boolean matches(Path file,PublicKey key) {
  try {
   if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>128)return false;
   String pin=Files.readString(file).strip();
   return pin.matches("[0-9a-f]{64}")&&MessageDigest.isEqual(HexFormat.of().parseHex(pin),MessageDigest.getInstance("SHA-256").digest(key.getEncoded()));
  } catch(IOException|GeneralSecurityException|RuntimeException e){return false;}
 }
}
