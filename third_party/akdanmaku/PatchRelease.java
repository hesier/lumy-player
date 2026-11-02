import java.nio.file.Files;
import java.nio.file.Path;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Fixes worker shutdown without changing upstream frame scheduling or rendering. */
public final class PatchRelease implements Opcodes {
    public static void main(String[] args) throws Exception {
        ClassReader reader = new ClassReader(Files.readAllBytes(Path.of(args[0])));
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        int[] patched = {0};
        reader.accept(new ClassVisitor(ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                MethodVisitor original = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!descriptor.equals("()V")) return original;
                if (reader.getClassName().endsWith("/DanmakuEngine") && name.startsWith("release$")) {
                    return closeCache(original, patched);
                }
                if (reader.getClassName().endsWith("/DanmakuPlayer") && name.equals("release")) {
                    return wakeRenderer(original, patched);
                }
                return original;
            }
        }, 0);
        if (patched[0] != 1) throw new IllegalStateException("Expected one release patch, got " + patched[0]);
        Files.write(Path.of(args[1]), writer.toByteArray());
    }

    private static MethodVisitor closeCache(MethodVisitor original, int[] patched) {
        return new MethodVisitor(ASM9, original) {
            @Override
            public void visitInsn(int opcode) {
                if (opcode == RETURN) {
                    super.visitVarInsn(ALOAD, 0);
                    super.visitFieldInsn(GETFIELD, "com/kuaishou/akdanmaku/ecs/DanmakuEngine",
                            "context", "Lcom/kuaishou/akdanmaku/ecs/DanmakuContext;");
                    super.visitMethodInsn(INVOKEVIRTUAL, "com/kuaishou/akdanmaku/ecs/DanmakuContext",
                            "getCacheManager", "()Lcom/kuaishou/akdanmaku/cache/CacheManager;", false);
                    super.visitMethodInsn(INVOKEVIRTUAL, "com/kuaishou/akdanmaku/cache/CacheManager",
                            "release", "()V", false);
                    patched[0]++;
                }
                super.visitInsn(opcode);
            }
        };
    }

    private static MethodVisitor wakeRenderer(MethodVisitor original, int[] patched) {
        return new MethodVisitor(ASM9, original) {
            @Override
            public void visitMethodInsn(int opcode, String owner, String method,
                    String desc, boolean isInterface) {
                if (owner.equals("android/os/HandlerThread") && method.equals("quitSafely")) {
                    // The quitSafely receiver stays on the stack; started is already false.
                    super.visitVarInsn(ALOAD, 0);
                    super.visitFieldInsn(GETFIELD, "com/kuaishou/akdanmaku/ui/DanmakuPlayer",
                            "drawSemaphore", "Ljava/util/concurrent/Semaphore;");
                    super.visitMethodInsn(INVOKEVIRTUAL, "java/util/concurrent/Semaphore",
                            "release", "()V", false);
                    patched[0]++;
                }
                super.visitMethodInsn(opcode, owner, method, desc, isInterface);
            }
        };
    }
}
