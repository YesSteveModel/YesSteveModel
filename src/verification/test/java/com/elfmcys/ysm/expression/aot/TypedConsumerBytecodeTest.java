package com.elfmcys.ysm.expression.aot;

import com.elfmcys.ysm.geckolib3.core.controller.CodedAnimationController;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class TypedConsumerBytecodeTest {
    @Test
    void primitiveConsumersKeepDirectTypedCallsWithoutResultOrLambdaAdapters() throws IOException {
        Set<String> engineCalls = new HashSet<>();
        inspect(EntityExpressionRuntime.Evaluation.class,
            Set.of("evaluate", "executeFloat", "executeBoolean", "executeVoid", "codedController"), engineCalls);
        assertTrue(engineCalls.contains("executeFloat:F"));
        assertTrue(engineCalls.contains("executeBoolean:Z"));
        assertTrue(engineCalls.contains("executeVoid:V"));
        inspect(CodedAnimationController.class, Set.of("evalMolangPredicate"), new HashSet<>());
    }

    private static void inspect(Class<?> owner, Set<String> methods, Set<String> engineCalls) throws IOException {
        Set<String> found = new HashSet<>();
        try (var input = owner.getResourceAsStream('/' + owner.getName().replace('.', '/') + ".class")) {
            assertNotNull(input);
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                    if (!methods.contains(name))
                        return null;
                    found.add(name);
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitInvokeDynamicInsn(String call, String type, Handle bootstrap,
                            Object... arguments) {
                            fail(owner.getName() + '.' + name + " introduces an invokedynamic adapter");
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String target, String call, String type,
                            boolean isInterface) {
                            assertFalse(target.startsWith("java/util/function/"), target);
                            assertFalse(target.equals("dev/semaweave/RuntimeValue")
                                || target.equals("dev/semaweave/RuntimeValues")
                                || target.equals("java/util/OptionalInt"), target);
                            assertFalse(
                                call.equals("valueOf")
                                    && (target.equals("java/lang/Float") || target.equals("java/lang/Boolean")),
                                target);
                            if (target.equals("dev/semaweave/CompiledModuleInstance")) {
                                assertTrue(Set.of("executeFloat", "executeBoolean", "executeVoid").contains(call));
                                engineCalls.add(call + ':' + type.substring(type.lastIndexOf(')') + 1));
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        assertEquals(methods, found);
    }
}
