import java.nio.file.Files;
import java.nio.file.Path;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Applies cache-budget hooks only to the pinned upstream manager and worker. */
public final class PatchCache implements Opcodes {
    private static final String MANAGER = "com/kuaishou/akdanmaku/cache/CacheManager";
    private static final String BUDGET = "com/kuaishou/akdanmaku/LumyCacheBudget";
    private static final String REQUEST = "(Lcom/kuaishou/akdanmaku/data/DanmakuItem;"
            + "Lcom/kuaishou/akdanmaku/ui/DanmakuDisplayer;"
            + "Lcom/kuaishou/akdanmaku/DanmakuConfig;)V";

    public static void main(String[] args) throws Exception {
        ClassReader reader = new ClassReader(Files.readAllBytes(Path.of(args[0])));
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        boolean worker = reader.getClassName().equals(MANAGER + "$CacheHandler");
        if (!worker && !reader.getClassName().equals(MANAGER)) {
            throw new IllegalArgumentException("Unexpected cache class: " + reader.getClassName());
        }
        int[] patched = {0};
        reader.accept(new ClassVisitor(ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                MethodVisitor original = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (worker && name.equals("handleMessage") && descriptor.equals("(Landroid/os/Message;)V")) {
                    patched[0]++;
                    return workerHooks(original);
                }
                if (!worker && descriptor.equals(REQUEST)
                        && (name.equals("requestMeasure") || name.equals("requestBuildCache"))) {
                    patched[0]++;
                    return admissionGate(original, name.equals("requestMeasure") ? 0 : 1);
                }
                if (!worker && descriptor.equals("()V")
                        && (name.equals("cancelAllRequests") || name.equals("release"))) {
                    patched[0]++;
                    return lifecycleHook(original, name.equals("release") ? "close" : "cancel");
                }
                return original;
            }
        }, 0);
        int expected = worker ? 1 : 4;
        if (patched[0] != expected) throw new IllegalStateException("Expected " + expected + " hooks, got " + patched[0]);
        Files.write(Path.of(args[1]), writer.toByteArray());
    }

    private static MethodVisitor admissionGate(MethodVisitor original, int kind) {
        return new MethodVisitor(ASM9, original) {
            @Override
            public void visitCode() {
                super.visitCode();
                super.visitVarInsn(ALOAD, 0);
                super.visitVarInsn(ALOAD, 1);
                super.visitInsn(kind == 0 ? ICONST_0 : ICONST_1);
                super.visitMethodInsn(INVOKESTATIC, BUDGET, "admit",
                        "(Ljava/lang/Object;Lcom/kuaishou/akdanmaku/data/DanmakuItem;I)Z", false);
                Label admitted = new Label();
                super.visitJumpInsn(IFNE, admitted);
                super.visitInsn(RETURN);
                super.visitLabel(admitted);
                super.visitFrame(F_SAME, 0, null, 0, null);
            }
        };
    }

    private static MethodVisitor lifecycleHook(MethodVisitor original, String hook) {
        return new MethodVisitor(ASM9, original) {
            @Override
            public void visitCode() {
                super.visitCode();
                super.visitVarInsn(ALOAD, 0);
                super.visitMethodInsn(INVOKESTATIC, BUDGET, hook, "(Ljava/lang/Object;)V", false);
            }
        };
    }

    private static MethodVisitor workerHooks(MethodVisitor original) {
        return new MethodVisitor(ASM9, original) {
            private void manager() {
                super.visitVarInsn(ALOAD, 0);
                super.visitFieldInsn(GETFIELD, MANAGER + "$CacheHandler", "this$0", "L" + MANAGER + ";");
            }

            @Override
            public void visitCode() {
                super.visitCode();
                manager();
                super.visitVarInsn(ALOAD, 1);
                super.visitMethodInsn(INVOKESTATIC, BUDGET, "taskStarted", "(Ljava/lang/Object;Landroid/os/Message;)V", false);
            }

            @Override
            public void visitInsn(int opcode) {
                if (opcode == RETURN || opcode == ATHROW) {
                    manager();
                    super.visitMethodInsn(INVOKESTATIC, BUDGET, "taskFinished", "(Ljava/lang/Object;)V", false);
                }
                super.visitInsn(opcode);
            }
        };
    }
}
