package ru.warland.auth;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.security.interfaces.*;
import java.security.spec.*;
import java.util.*;

/** Private persistent RSA identity. Existing invalid state is NEVER silently replaced. */
public final class ServerIdentity {
 private ServerIdentity() {}
 private static final int MAGIC=0x574c4931, MAX=8192;
 public static KeyPair loadOrCreate(Path directory) throws IOException, GeneralSecurityException {
  Path dir=directory.toAbsolutePath().normalize();
  for(Path p=dir;p!=null;p=p.getParent()) if(Files.isSymbolicLink(p)) throw new IOException("Linked identity directory");
  if(!Files.exists(dir,LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(dir,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
  if(!Files.isDirectory(dir,LinkOption.NOFOLLOW_LINKS)||!Files.getPosixFilePermissions(dir,LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rwx------"))) throw new IOException("Identity directory must be private");
  Path target=dir.resolve("identity.bin");
  if(!Files.exists(target,LinkOption.NOFOLLOW_LINKS)) {
   KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);KeyPair key=generator.generateKeyPair();
   byte[] priv=key.getPrivate().getEncoded(),pub=key.getPublic().getEncoded();
   ByteBuffer bytes=ByteBuffer.allocate(12+priv.length+pub.length).putInt(MAGIC).putInt(priv.length).put(priv).putInt(pub.length).put(pub);bytes.flip();
   try(FileChannel out=FileChannel.open(target,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
    while(bytes.hasRemaining())out.write(bytes);out.force(true);
   } finally {Arrays.fill(priv,(byte)0);Arrays.fill(bytes.array(),(byte)0);}
   // A failed/partial creation remains for operator inspection; never generate a replacement on retry.
   try(FileChannel parent=FileChannel.open(dir,StandardOpenOption.READ)){parent.force(true);}
  }
  if(!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS)||!Files.getPosixFilePermissions(target,LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rw-------"))
    ||!Files.getOwner(target,LinkOption.NOFOLLOW_LINKS).equals(Files.getOwner(dir,LinkOption.NOFOLLOW_LINKS))
    ||((Number)Files.getAttribute(target,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).intValue()!=1)throw new IOException("Unsafe identity file");
  byte[] data;
  try(FileChannel in=FileChannel.open(target,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
   long size=in.size();if(size<16||size>MAX)throw new IOException("Invalid identity size");
   ByteBuffer b=ByteBuffer.allocate((int)size);while(b.hasRemaining())if(in.read(b)<0)throw new EOFException();
   if(in.size()!=size)throw new IOException("Changed identity file");data=b.array();
  }
  byte[] privateBytes=null;
  try {
   ByteBuffer b=ByteBuffer.wrap(data);if(b.getInt()!=MAGIC)throw new IOException("Invalid identity version");
   int n=b.getInt();if(n<512||n>4096||n>b.remaining()-4)throw new IOException("Invalid identity");
   privateBytes=new byte[n];b.get(privateBytes);int m=b.getInt();if(m<128||m>1024||m!=b.remaining())throw new IOException("Invalid identity");
   byte[] publicBytes=new byte[m];b.get(publicBytes);KeyFactory factory=KeyFactory.getInstance("RSA");
   PrivateKey priv=factory.generatePrivate(new PKCS8EncodedKeySpec(privateBytes));PublicKey pub=factory.generatePublic(new X509EncodedKeySpec(publicBytes));
   if(!(priv instanceof RSAPrivateCrtKey rsa)||!(pub instanceof RSAPublicKey publicRsa)||rsa.getModulus().bitLength()!=2048
    ||!rsa.getModulus().equals(publicRsa.getModulus())||!rsa.getPublicExponent().equals(publicRsa.getPublicExponent()))throw new IOException("Identity key mismatch");
   Signature check=Signature.getInstance("SHA256withRSA");byte[] probe="WarLand identity consistency".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
   check.initSign(priv);check.update(probe);byte[] proof=check.sign();check.initVerify(pub);check.update(probe);if(!check.verify(proof))throw new IOException("Identity key mismatch");
   return new KeyPair(pub,priv);
  }catch(java.nio.BufferUnderflowException e){throw new IOException("Invalid identity",e);}
  finally {Arrays.fill(data,(byte)0);if(privateBytes!=null)Arrays.fill(privateBytes,(byte)0);}
 }
 public static String fingerprint(PublicKey key) throws GeneralSecurityException {
  return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getEncoded()));
 }
}
