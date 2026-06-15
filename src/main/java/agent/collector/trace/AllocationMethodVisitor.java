package agent.collector.trace;

import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Label;

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
    private static final String REQUEST_CONTEXT = "agent/profiling/RequestProfilingContext";

    /** Internal names of NEW'd types awaiting their <init>, in LIFO order. */
    private final Deque<String> pendingNew = new ArrayDeque<>();
    private final boolean allocationDetail;
    private final boolean lineAllocationDetail;
    private final boolean deterministicLineDetail;
    private final String className;
    private final String methodName;
    private final String fileName;
    private int currentLine = -1;

    AllocationMethodVisitor(MethodVisitor mv, boolean allocationDetail,
                            boolean lineAllocationDetail,
                            boolean deterministicLineDetail,
                            String className, String methodName, String fileName) {
        super(Opcodes.ASM9, mv);
        this.allocationDetail = allocationDetail;
        this.lineAllocationDetail = lineAllocationDetail;
        this.deterministicLineDetail = deterministicLineDetail;
        this.className = className;
        this.methodName = methodName;
        this.fileName = fileName;
    }

    @Override
    public void visitLineNumber(int line, Label start) {
        currentLine = line;
        super.visitLineNumber(line, start);
        if (deterministicLineDetail && line > 0) {
            emitLineEnter(line);
        }
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

    /** stack: ..., ref -> DUP, INVOKESTATIC AllocationRecorder.record*(...). */
    private void emitRecord() {
        if (!allocationDetail && !lineAllocationDetail) {
            return;
        }
        super.visitInsn(Opcodes.DUP);
        if (lineAllocationDetail && currentLine > 0) {
            super.visitLdcInsn(className);
            super.visitLdcInsn(methodName);
            super.visitLdcInsn(fileName);
            super.visitLdcInsn(currentLine);
            super.visitMethodInsn(Opcodes.INVOKESTATIC, RECORDER, "recordAt",
                "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V", false);
        } else {
            super.visitMethodInsn(Opcodes.INVOKESTATIC, RECORDER, "record",
                "(Ljava/lang/Object;)V", false);
        }
    }

    /** stack-neutral deterministic source-line probe. */
    private void emitLineEnter(int line) {
        super.visitLdcInsn(className);
        super.visitLdcInsn(methodName);
        super.visitLdcInsn(fileName);
        super.visitLdcInsn(line);
        super.visitMethodInsn(Opcodes.INVOKESTATIC, REQUEST_CONTEXT, "lineEnter",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V", false);
    }
}
