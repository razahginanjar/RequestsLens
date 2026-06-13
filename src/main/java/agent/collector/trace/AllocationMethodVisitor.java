package agent.collector.trace;

import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * ASM method visitor that injects a call to
 * {@code AllocationRecorder.record(Object)} immediately after each object/array
 * allocation site inside an instrumented method (Phase 6, Amendment A).
 *
 * <h2>Allocation sites handled</h2>
 * <ul>
 *   <li><b>Objects</b>: the {@code NEW … INVOKESPECIAL <init>} pair. We remember
 *       each {@code NEW} and, when its matching constructor returns, the fully
 *       constructed reference is on top of the stack — we DUP it and record. A
 *       LIFO stack of pending {@code NEW} types pairs nested allocations
 *       correctly and avoids mistaking {@code super()}/{@code this()} (which have
 *       no preceding {@code NEW}) for allocations.</li>
 *   <li><b>Arrays</b>: {@code NEWARRAY} (primitive, e.g. {@code byte[]}),
 *       {@code ANEWARRAY} (object arrays), {@code MULTIANEWARRAY} — the array
 *       reference is on the stack right after the single bytecode.</li>
 * </ul>
 *
 * <h2>Stack safety</h2>
 * The injected {@code DUP; INVOKESTATIC record(Object)V} is stack-neutral (DUP
 * +1, the static call consumes 1) and adds no branches, so it only raises max
 * stack by one — handled by COMPUTE_MAXS on the class writer.
 */
final class AllocationMethodVisitor extends MethodVisitor {

    private static final String RECORDER = "agent/profiling/AllocationRecorder";

    /** Internal names of NEW'd types awaiting their <init>, in LIFO order. */
    private final Deque<String> pendingNew = new ArrayDeque<>();

    AllocationMethodVisitor(MethodVisitor mv) {
        super(Opcodes.ASM9, mv);
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
        if (opcode == Opcodes.NEW) {
            pendingNew.push(type);          // record after its constructor returns
            super.visitTypeInsn(opcode, type);
        } else if (opcode == Opcodes.ANEWARRAY) {
            super.visitTypeInsn(opcode, type);
            emitRecord();                   // object-array ref now on stack
        } else {
            super.visitTypeInsn(opcode, type);
        }
    }

    @Override
    public void visitIntInsn(int opcode, int operand) {
        super.visitIntInsn(opcode, operand);
        if (opcode == Opcodes.NEWARRAY) {   // primitive array: byte[], int[], …
            emitRecord();
        }
    }

    @Override
    public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
        super.visitMultiANewArrayInsn(descriptor, numDimensions);
        emitRecord();
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name,
                                String descriptor, boolean isInterface) {
        boolean pairedNew = opcode == Opcodes.INVOKESPECIAL
            && "<init>".equals(name)
            && !pendingNew.isEmpty()
            && pendingNew.peek().equals(owner);
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        if (pairedNew) {
            pendingNew.pop();
            emitRecord();                   // fully-constructed object ref on stack
        }
    }

    /** stack: …, ref  →  DUP, INVOKESTATIC AllocationRecorder.record(Object). */
    private void emitRecord() {
        super.visitInsn(Opcodes.DUP);
        super.visitMethodInsn(Opcodes.INVOKESTATIC, RECORDER, "record",
            "(Ljava/lang/Object;)V", false);
    }
}
