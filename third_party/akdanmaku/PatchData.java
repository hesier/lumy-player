import java.nio.file.Files;
import java.nio.file.Path;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Replaces only the pinned data slice's two incorrect boundary searches. */
public final class PatchData implements Opcodes {
    public static void main(String[] args) throws Exception {
        ClassReader reader = new ClassReader(Files.readAllBytes(Path.of(args[0])));
        if (!reader.getClassName().equals("com/kuaishou/akdanmaku/ecs/system/DataSystem")) {
            throw new IllegalArgumentException("Unexpected data class: " + reader.getClassName());
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        int[] patched = {0, 0};
        reader.accept(new ClassVisitor(ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                MethodVisitor original = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals("updateCurrentSlice") || !descriptor.equals("()V")) return original;
                return sliceBounds(original, patched);
            }
        }, 0);
        if (patched[0] != 1 || patched[1] != 1) {
            throw new IllegalStateException("Expected one lower and upper bound hook");
        }
        Files.write(Path.of(args[1]), writer.toByteArray());
    }

    private static MethodVisitor sliceBounds(MethodVisitor original, int[] patched) {
        return new MethodVisitor(ASM9, original) {
            @Override
            public void visitMethodInsn(int opcode, String owner, String name,
                    String descriptor, boolean isInterface) {
                boolean lower = name.equals("binarySearchAtLeast");
                if (opcode == INVOKESTATIC && owner.equals("com/kuaishou/akdanmaku/ext/CollectionsKt")
                        && (lower || name.equals("binarySearchAtMost"))
                        && descriptor.equals("(Ljava/util/List;Ljava/lang/Comparable;Lkotlin/jvm/functions/Function1;)I")) {
                    // Discard the time selector and unbox the timestamp already on the stack.
                    super.visitInsn(POP);
                    super.visitTypeInsn(CHECKCAST, "java/lang/Number");
                    super.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number", "longValue", "()J", false);
                    super.visitMethodInsn(INVOKESTATIC, "com/kuaishou/akdanmaku/LumyDataBounds",
                            lower ? "lowerBound" : "upperBound", "(Ljava/util/List;J)I", false);
                    patched[lower ? 0 : 1]++;
                    return;
                }
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            }
        };
    }
}
