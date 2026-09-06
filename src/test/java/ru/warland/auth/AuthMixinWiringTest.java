package ru.warland.auth;

import java.io.InputStream;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import static org.junit.jupiter.api.Assertions.*;

/** Checks actual pinned Minecraft bytecode without class initialization; not a Mixin launch test. */
class AuthMixinWiringTest {
    private static Set<String> methods(String name) throws Exception {
        Set<String> result = new HashSet<>();
        try (InputStream stream = AuthMixinWiringTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(stream, name);
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String method, String descriptor, String signature, String[] exceptions) {
                    result.add(method); result.add(method + descriptor); return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return result;
    }
    private static List<String> targets(String mixin) throws Exception {
        List<String> names = new ArrayList<>();
        try (InputStream stream = AuthMixinWiringTest.class.getClassLoader().getResourceAsStream("ru/warland/mixin/" + mixin + ".class")) {
            assertNotNull(stream, mixin);
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String method, String desc, String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                            if (!descriptor.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) return null;
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                @Override public AnnotationVisitor visitArray(String name) {
                                    if (!name.equals("method")) return null;
                                    return new AnnotationVisitor(Opcodes.ASM9) {
                                        @Override public void visit(String ignored, Object value) { names.add((String)value); }
                                    };
                                }
                            };
                        }
                    };
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        assertFalse(names.isEmpty(), mixin); return names;
    }
    @Test void everyDeclaredInjectionExistsInPinnedMinecraft() throws Exception {
        Map<String,String> classes = Map.of(
            "AuthConnectionMixin", "net/minecraft/network/ClientConnection",
            "AuthPlayMixin", "net/minecraft/server/network/ServerPlayNetworkHandler",
            "AuthCommonMixin", "net/minecraft/server/network/ServerCommonNetworkHandler",
            "AuthPlayerMixin", "net/minecraft/server/network/ServerPlayerEntity",
            "AuthPickupMixin", "net/minecraft/entity/ItemEntity",
            "AuthModerationMixin", "ru/warland/moderation/Moderation");
        for (var entry : classes.entrySet()) {
            Set<String> actual = methods(entry.getValue());
            for (String target : targets(entry.getKey())) assertTrue(actual.contains(target), entry.getKey() + " missing " + target);
        }
    }
    @Test void everyPlayPacketHandlerHasAnExplicitMainThreadGate() throws Exception {
        Set<String> listener = methods("net/minecraft/network/listener/ServerPlayPacketListener");
        Set<String> gates = new HashSet<>(targets("AuthPlayMixin"));
        for (String method : listener) if (method.startsWith("on") && !method.contains("(")) assertTrue(gates.contains(method), "Ungated handler: " + method);
    }
}
