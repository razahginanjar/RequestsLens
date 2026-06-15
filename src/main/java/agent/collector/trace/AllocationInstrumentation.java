package agent.collector.trace;

import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.pool.TypePool;

/**
 * Builds the Byte Buddy {@link AsmVisitorWrapper} that wraps matched methods with
 * {@link AllocationMethodVisitor} (Phase 6, Amendment A — reliable per-object
 * allocation capture).
 *
 * <p>Requests {@code COMPUTE_MAXS} on the class writer because the injected
 * {@code DUP} raises max stack by one.
 */
public final class AllocationInstrumentation {

    private AllocationInstrumentation() {}

    public static AsmVisitorWrapper forMethods(ElementMatcher<? super MethodDescription> methods) {
        return forMethods(methods, false);
    }

    public static AsmVisitorWrapper forMethods(ElementMatcher<? super MethodDescription> methods,
                                               boolean lineAllocationDetail) {
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
                    return new AllocationMethodVisitor(methodVisitor, lineAllocationDetail,
                        className, methodName, fileName);
                }
            })
            .writerFlags(ClassWriter.COMPUTE_MAXS);
    }
}
