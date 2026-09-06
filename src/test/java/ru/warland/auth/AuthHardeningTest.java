package ru.warland.auth;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import static org.junit.jupiter.api.Assertions.*;
class AuthHardeningTest {
 @Test void onlyFullUnsilencedOperatorContext() {
  assertTrue(RuntimePolicy.provisioningContext(false,true));
  assertFalse(RuntimePolicy.provisioningContext(true,true));
  assertFalse(RuntimePolicy.provisioningContext(false,false));
  assertFalse(RuntimePolicy.provisioningContext(true,false));
 }
 private static ClassReader reader(String name) throws Exception {
  try(InputStream in=AuthHardeningTest.class.getClassLoader().getResourceAsStream(name+".class")) { assertNotNull(in,name); return new ClassReader(in); }
 }
 @Test void pinnedConfigurationTransitionsAndSilentFieldExist() throws Exception {
  Set<String> methods=new HashSet<>(), fields=new HashSet<>();
  reader("net/minecraft/server/network/ServerConfigurationNetworkHandler").accept(new ClassVisitor(Opcodes.ASM9) {
   @Override public MethodVisitor visitMethod(int a,String n,String d,String s,String[] e){methods.add(n);return null;}
  },ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
  assertTrue(methods.containsAll(Set.of("onReady","endConfiguration")));
  reader("net/minecraft/server/command/ServerCommandSource").accept(new ClassVisitor(Opcodes.ASM9) {
   @Override public FieldVisitor visitField(int a,String n,String d,String s,Object v){fields.add(n+d);return null;}
  },ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
  assertTrue(fields.contains("silentZ"));
 }
 @Test void bothConfigurationBarriersAreRequiredAndRegistered() throws Exception {
  Set<String> targets=new HashSet<>();
  reader("ru/warland/mixin/AuthConfigurationMixin").accept(new ClassVisitor(Opcodes.ASM9) {
   @Override public MethodVisitor visitMethod(int a,String n,String d,String s,String[] e){return new MethodVisitor(Opcodes.ASM9){
    @Override public AnnotationVisitor visitAnnotation(String desc,boolean visible){
     if(!desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;"))return null;
     return new AnnotationVisitor(Opcodes.ASM9){@Override public AnnotationVisitor visitArray(String key){
      if(!key.equals("method"))return null;
      return new AnnotationVisitor(Opcodes.ASM9){@Override public void visit(String ignored,Object value){targets.add((String)value);}};
     }};
    }
   };}
  },ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
  assertEquals(Set.of("onReady","endConfiguration"),targets);
  try(InputStream in=AuthHardeningTest.class.getClassLoader().getResourceAsStream("warland.mixins.json")) {
   assertNotNull(in);String json=new String(in.readAllBytes(),StandardCharsets.UTF_8).replaceAll("\\s+","");
   assertTrue(json.contains("\"AuthConfigurationMixin\""));assertTrue(json.contains("\"required\":true"));assertTrue(json.contains("\"defaultRequire\":1"));
  }
 }
}
