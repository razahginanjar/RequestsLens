package agent.collector.trace;

import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.pool.TypePool;

/**
 * Builds the Byte Buddy {@link AsmVisitorWrapper} that wraps matched methods with
 * {@link AllocationMethodVisitor} (Phase 6, Amendment A — reliable per-object
 * allocation capture).
 *
 * <p>Requests {@code COMPUTE_MAXS | COMPUTE_FRAMES} on the class writer because
 * deterministic line probes and allocation probes add instructions inside
 * methods that may already have stack map frames.
 */
public final class AllocationInstrumentation {

    private AllocationInstrumentation() {}

    public static AsmVisitorWrapper forMethods(ElementMatcher<? super MethodDescription> methods) {
        return forMethods(methods, false, false, true);
    }

    public static AsmVisitorWrapper forMethods(ElementMatcher<? super MethodDescription> methods,
                                               boolean lineAllocationDetail) {
        return forMethods(methods, lineAllocationDetail, false, true);
    }

    public static AsmVisitorWrapper forMethods(ElementMatcher<? super MethodDescription> methods,
                                               boolean lineAllocationDetail,
                                               boolean deterministicLineDetail,
                                               boolean allocationDetail) {
        return new AsmVisitorWrapper.ForDeclaredMethods()
            .method(methods, new AsmVisitorWrapper.ForDeclaredMethods.MethodVisitorWrapper() {
                @Override
                public MethodVisitor wrap(TypeDescription instrumentedType,
                                          MethodDescription instrumentedMethod,
                                          MethodVisitor methodVisitor,
                                          Implementation.Context implementationContext,
                                          TypePool typePool,
                                          int writerFlags, int readerFlags) {
                    String className = instrumentedType.getName();
                    String methodName = instrumentedMethod.getActualName();
                    String fileName = instrumentedType.getSimpleName() + ".java";
                    return new AllocationMethodVisitor(methodVisitor, allocationDetail,
                        lineAllocationDetail, deterministicLineDetail, className,
                        methodName, fileName);
                }
            })
            .writerFlags(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES)
            .readerFlags(ClassReader.EXPAND_FRAMES);
    }
}
