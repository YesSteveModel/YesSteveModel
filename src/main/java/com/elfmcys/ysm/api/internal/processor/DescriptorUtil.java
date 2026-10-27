package com.elfmcys.ysm.api.internal.processor;

import com.elfmcys.ysm.api.internal.processor.asm.Type;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

final class DescriptorUtil {
    private DescriptorUtil() {}

    static String methodDescriptor(ExecutableElement method, Types types, Elements elements) {
        StringBuilder descriptor = new StringBuilder("(");
        for (var parameter : method.getParameters()) {
            descriptor.append(typeDescriptor(types.erasure(parameter.asType()), types, elements));
        }
        return descriptor.append(')').append(typeDescriptor(types.erasure(method.getReturnType()), types, elements))
            .toString();
    }

    static String typeDescriptor(TypeMirror type, Types types, Elements elements) {
        return switch (type.getKind()) {
            case BOOLEAN -> "Z";
            case BYTE -> "B";
            case SHORT -> "S";
            case INT -> "I";
            case LONG -> "J";
            case CHAR -> "C";
            case FLOAT -> "F";
            case DOUBLE -> "D";
            case VOID -> "V";
            case ARRAY -> "[" + typeDescriptor(((ArrayType)type).getComponentType(), types, elements);
            case DECLARED -> {
                TypeElement element = (TypeElement)((DeclaredType)type).asElement();
                yield "L" + elements.getBinaryName(element).toString().replace('.', '/') + ";";
            }
            case TYPEVAR, WILDCARD, INTERSECTION, ERROR -> typeDescriptor(types.erasure(type), types, elements);
            default -> throw new IllegalArgumentException("Unsupported type in descriptor: " + type);
        };
    }

    static List<String> referencedInternalNames(String descriptor) {
        List<String> result = new ArrayList<>();
        Type type = descriptor.charAt(0) == '(' ? Type.getMethodType(descriptor) : Type.getType(descriptor);
        if (type.getSort() == Type.METHOD) {
            collectInternalNames(type.getReturnType(), result);
            for (Type argument : type.getArgumentTypes()) {
                collectInternalNames(argument, result);
            }
        } else {
            collectInternalNames(type, result);
        }
        return result;
    }

    static void collectInternalNames(Type type, List<String> target) {
        while (type.getSort() == Type.ARRAY) {
            type = type.getElementType();
        }
        if (type.getSort() == Type.OBJECT) {
            target.add(type.getInternalName());
        }
    }

    static String binaryName(String internalName) {
        return internalName.replace('/', '.');
    }

    static String internalName(String binaryName) {
        return binaryName.replace('.', '/');
    }
}
