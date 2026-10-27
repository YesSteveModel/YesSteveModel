package com.elfmcys.ysm.api.internal.processor;

import com.elfmcys.ysm.api.molang.DeclarationSchema;
import com.elfmcys.ysm.api.molang.DeclarationSchema.*;
import java.io.*;
import java.util.*;
import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.ElementFilter;
import javax.tools.*;

/** Both annotation surfaces normalize into this model and this emitter. */
public final class UnifiedDeclarationProcessor extends AbstractProcessor {
    private static final String API = "com.elfmcys.ysm.api.annotation.";
    private static final String BUILTIN = "com.elfmcys.ysm.expression.host.builtin.";
    private static final String CONTEXT = "com.elfmcys.ysm.api.molang.MolangContextView";
    private static final String ACTION = "com.elfmcys.ysm.api.molang.MolangActionView";
    private static final String OWNER = "com.elfmcys.ysm.client.entity.AnimatableEntity";
    private static final String COMPONENT = "com.elfmcys.ysm.expression.host.EntityComponent";
    private final Set<String> emitted = new HashSet<>();
    private final Set<String> builtinResources = new TreeSet<>();
    private boolean indexWritten;

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(API + "YsmEntityComponent", API + "MolangFunction", BUILTIN + "BuiltinComponent",
            BUILTIN + "BuiltinFunction");
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.RELEASE_17;
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        for (String marker : List.of(API + "YsmEntityComponent", BUILTIN + "BuiltinComponent")) {
            TypeElement annotation = processingEnv.getElementUtils().getTypeElement(marker);
            if (annotation == null)
                continue;
            for (Element element : round.getElementsAnnotatedWith(annotation)) {
                if (!(element instanceof TypeElement owner))
                    continue;
                String binary = binary(owner);
                if (!emitted.add(binary))
                    continue;
                try {
                    generate(owner, marker.startsWith(BUILTIN));
                } catch (IllegalArgumentException | IOException failure) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, failure.getMessage(), owner);
                }
            }
        }
        if (round.processingOver() && !indexWritten && !builtinResources.isEmpty()) {
            indexWritten = true;
            try (var writer = processingEnv.getFiler()
                .createResource(StandardLocation.CLASS_OUTPUT, "", DeclarationSchema.RESOURCE_ROOT + "builtin.index")
                .openWriter()) {
                for (String resource : builtinResources)
                    writer.write(resource + "\n");
            } catch (IOException failure) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, failure.toString());
            }
        }
        return false;
    }

    private void generate(TypeElement owner, boolean builtin) throws IOException {
        visible(owner);
        if (!owner.getTypeParameters().isEmpty())
            throw invalid("declaration class must be closed");
        var marker = values(annotation(owner, builtin ? BUILTIN + "BuiltinComponent" : API + "YsmEntityComponent"));
        String namespace = text(marker, "value");
        String componentSubject = subject(owner.asType(), COMPONENT);
        String packageName = processingEnv.getElementUtils().getPackageOf(owner).getQualifiedName().toString();
        String ownerName = binary(owner),
            simple = ownerName.substring(packageName.isEmpty() ? 0 : packageName.length() + 1) + "MolangBindings";
        String helper = packageName.isEmpty() ? simple : packageName + "." + simple;
        var recipes = new LinkedHashMap<String, Value>();
        var members = new ArrayList<Member>();
        for (ExecutableElement method : ElementFilter.methodsIn(processingEnv.getElementUtils().getAllMembers(owner))) {
            var function = annotation(method, builtin ? BUILTIN + "BuiltinFunction" : API + "MolangFunction");
            if (function == null)
                continue;
            if (!method.getModifiers().contains(Modifier.PUBLIC) || !method.getTypeParameters().isEmpty())
                throw invalid("method must be public and non-generic");
            var signature =
                (ExecutableType)processingEnv.getTypeUtils().asMemberOf((DeclaredType)owner.asType(), method);
            var parameters = signature.getParameterTypes();
            if (parameters.isEmpty())
                throw invalid("first parameter must be an exact Molang context view");
            String viewType = processingEnv.getTypeUtils().erasure(parameters.get(0)).toString();
            if (!viewType.equals(CONTEXT) && !viewType.equals(ACTION))
                throw invalid("first parameter must be an exact MolangContextView<E> or MolangActionView<E>");
            String entity = subject(parameters.get(0), viewType);
            if (entity.isEmpty())
                throw invalid("first parameter must be a closed Molang context view");
            boolean stat = method.getModifiers().contains(Modifier.STATIC);
            if (!stat && componentSubject.isEmpty())
                throw invalid("member declaration requires EntityComponent<E>");
            var attrs = values(function);
            String memberNamespace = text(attrs, "namespace").isEmpty() ? namespace : text(attrs, "namespace");
            String name = identifier(text(attrs, "value").isEmpty()
                ? method.getSimpleName().toString().toLowerCase(Locale.ROOT) : text(attrs, "value"));
            String effect = enumText(attrs, "effect");
            boolean optional =
                method.getParameters().stream().anyMatch(p -> annotation(p, API + "DefaultValue") != null);
            boolean tail = method.isVarArgs();
            if (tail && optional)
                throw invalid("defaults and varargs cannot be mixed");
            int offset = optional || tail ? 2 : 1;
            if (offset == 2 && (parameters.size() < 2 || parameters.get(1).getKind() != TypeKind.INT))
                throw invalid("optional/variadic declaration requires int argc second");
            int fixedCount = parameters.size() - offset - (tail ? 1 : 0);
            var fixed = new ArrayList<Value>();
            var defaults = new ArrayList<String>();
            for (int i = 0; i < parameters.size(); i++) {
                var d = annotation(method.getParameters().get(i), API + "DefaultValue");
                if (d != null && (i < offset || tail && i == parameters.size() - 1))
                    throw invalid("context, argc and tail cannot have defaults");
            }
            for (int i = 0; i < fixedCount; i++) {
                fixed.add(value(parameters.get(i + offset), recipes, new HashSet<>(), 1, false));
                var d = annotation(method.getParameters().get(i + offset), API + "DefaultValue");
                if (d != null)
                    defaults.add(text(values(d), "value"));
                else if (!defaults.isEmpty())
                    throw invalid("defaults must form a continuous suffix");
            }
            Value variadic = tail ? value(((ArrayType)parameters.get(parameters.size() - 1)).getComponentType(),
                recipes, new HashSet<>(), 1, false) : null;
            int min = integer(attrs, "minArgs"), max = integer(attrs, "maxArgs");
            if (min < -1 || max < -1)
                throw invalid("arity may only use -1 as the unspecified value");
            if (min == -1)
                min = fixedCount - defaults.size();
            if (max == -1) {
                if (tail)
                    throw invalid("variadic declaration requires finite maxArgs");
                max = fixedCount;
            }
            Value result = value(signature.getReturnType(), recipes, new HashSet<>(), 1, true);
            var erased = (ExecutableType)processingEnv.getTypeUtils().erasure(method.asType());
            String descriptor =
                "(" + erased.getParameterTypes().stream().map(this::descriptor).reduce("", String::concat) + ")"
                    + descriptor(erased.getReturnType());
            members.add(new Member(memberNamespace, name, method.getSimpleName().toString(), descriptor,
                "m" + members.size(), entity, stat, viewType.equals(ACTION) ? "ACTION" : "CONTEXT", effect, min, max,
                fixed, defaults, variadic, result, annotation(method, API + "MolangNullable") != null));
        }
        var dependencies = new ArrayList<Dependency>();
        AnnotationValue deps = marker.get("dependencies");
        if (deps != null)
            for (var d : (List<? extends AnnotationValue>)deps.getValue()) {
                var v = values((AnnotationMirror)d.getValue());
                dependencies.add(new Dependency(text(v, "modId"), text(v, "version")));
            }
        dependencies.sort(Comparator.comparing(Dependency::modId));
        members.sort(Comparator.comparing(Member::symbol).thenComparing(Member::descriptor));
        var schema = new DeclarationSchema(ownerName, helper, namespace, text(marker, "modId"), builtin,
            "MANUAL".equals(enumText(marker, "mode")), componentSubject, dependencies, members);
        boolean instance = members.stream().anyMatch(m -> !m.isStatic());
        if (instance)
            constructor(owner, componentSubject);
        String source = emit(schema, owner.getQualifiedName().toString(), packageName, simple, recipes.values());
        try (var writer = processingEnv.getFiler().createSourceFile(helper, owner).openWriter()) {
            writer.write(source);
        }
        String resource = DeclarationSchema.RESOURCE_ROOT + ownerName + ".bin";
        try (var output = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", resource, owner)
            .openOutputStream()) {
            output.write(schema.encode());
        }
        if (builtin)
            builtinResources.add(resource);
    }

    private Value value(TypeMirror mirror, Map<String, Value> known, Set<String> active, int depth, boolean result) {
        if (depth > 3)
            throw invalid("only flat record and one-dimensional array schemas are supported");
        String key = mirror.toString();
        if (!active.add(key))
            throw invalid("recursive Java value schema");
        String name = processingEnv.getTypeUtils().erasure(mirror).toString();
        String kind = switch (mirror.getKind()) {
            case FLOAT -> "FLOAT";
            case INT -> "INT";
            case BOOLEAN -> "BOOLEAN";
            case VOID -> "VOID";
            default -> "";
        };
        if (Map.of("java.lang.Float", "FLOAT", "java.lang.Integer", "INT", "java.lang.Boolean", "BOOLEAN",
            "java.lang.String", "STRING").containsKey(name))
            kind = Map.of("java.lang.Float", "FLOAT", "java.lang.Integer", "INT", "java.lang.Boolean", "BOOLEAN",
                "java.lang.String", "STRING").get(name);
        Value element = null;
        var fields = new ArrayList<Field>();
        if (mirror instanceof DeclaredType declared) {
            TypeElement type = (TypeElement)declared.asElement();
            visible(type);
            if (name.equals("java.util.List")) {
                kind = "LIST";
                if (declared.getTypeArguments().size() != 1)
                    throw invalid("raw List is forbidden");
                element = value(declared.getTypeArguments().get(0), known, active, depth + 1, false);
            } else if (name.equals("it.unimi.dsi.fastutil.floats.FloatList")) {
                kind = "FLOAT_LIST";
                element = new Value("FLOAT", "float", "", null, List.of());
            } else if (type.getKind() == ElementKind.RECORD) {
                kind = "RECORD";
                if (!type.getTypeParameters().isEmpty())
                    throw invalid("generic record is forbidden");
                for (var f : type.getRecordComponents())
                    fields.add(new Field(recordField(f.getSimpleName().toString()),
                        value(f.asType(), known, active, depth + 1, false)));
            }
            if ((kind.equals("RECORD") || kind.equals("LIST")) && !name.startsWith("java.")
                && processingEnv.getElementUtils().getTypeElement(name) != null
                && com.sun.source.util.Trees.instance(processingEnv).getPath(type) == null
                && !name.startsWith("com.elfmcys.ysm."))
                throw invalid("public carrier must belong to this compilation or the YSM API");
        }
        if (kind.isEmpty() || kind.equals("VOID") && !result)
            throw invalid("unsupported Java Molang value " + key);
        if (mirror instanceof DeclaredType declared)
            name = binary((TypeElement)declared.asElement());
        Value recipe = new Value(kind, name, "t" + known.size(), element, fields);
        Value previous = known.putIfAbsent(key, recipe);
        active.remove(key);
        return previous == null ? recipe : previous;
    }

    private String subject(TypeMirror mirror, String target) {
        if (!(mirror instanceof DeclaredType declared))
            return "";
        if (processingEnv.getTypeUtils().erasure(mirror).toString().equals(target)) {
            var args = declared.getTypeArguments();
            if (args.size() != 1 || !(args.get(0) instanceof DeclaredType concrete)
                || !concrete.getTypeArguments().isEmpty())
                throw invalid("raw, wildcard or open subject is forbidden");
            TypeElement entity = processingEnv.getElementUtils().getTypeElement("net.minecraft.world.entity.Entity");
            if (entity != null && !processingEnv.getTypeUtils().isSubtype(args.get(0), entity.asType()))
                throw invalid("subject must extend Entity");
            return binary((TypeElement)concrete.asElement());
        }
        for (var parent : processingEnv.getTypeUtils().directSupertypes(mirror)) {
            String found = subject(parent, target);
            if (!found.isEmpty())
                return found;
        }
        return "";
    }

    private void constructor(TypeElement owner, String componentSubject) {
        if (owner.getModifiers().contains(Modifier.ABSTRACT)
            || owner.getEnclosingElement() instanceof TypeElement && !owner.getModifiers().contains(Modifier.STATIC))
            throw invalid("member declaration requires a concrete top-level or static class");
        for (var constructor : ElementFilter.constructorsIn(owner.getEnclosedElements())) {
            if (!constructor.getModifiers().contains(Modifier.PUBLIC) || !constructor.getTypeParameters().isEmpty()
                || constructor.getParameters().size() != 1)
                continue;
            var resolved =
                (ExecutableType)processingEnv.getTypeUtils().asMemberOf((DeclaredType)owner.asType(), constructor);
            TypeMirror parameter = resolved.getParameterTypes().get(0);
            if (processingEnv.getTypeUtils().erasure(parameter).toString().equals(OWNER)
                && subject(parameter, OWNER).equals(componentSubject))
                return;
        }
        throw invalid("component requires public AnimatableEntity<E> constructor with exactly the component subject");
    }

    private String emit(DeclarationSchema schema, String owner, String pkg, String simple, Collection<Value> values) {
        StringBuilder out = new StringBuilder(pkg.isEmpty() ? "" : "package " + pkg + ";\n");
        boolean instance = schema.members().stream().anyMatch(m -> !m.isStatic());
        out.append("@SuppressWarnings({\"unchecked\",\"rawtypes\"}) public final class ").append(simple);
        if (instance)
            out.append(" implements com.elfmcys.ysm.expression.host.EntityComponent<")
                .append(sourceName(schema.componentSubject())).append('>');
        out.append(" {\npublic static byte[] schema(){return java.util.Base64.getDecoder().decode(")
            .append(schemaEncoding(schema)).append(");}\n");
        if (instance) {
            out.append("private final ").append(owner).append(" provider;\npublic ").append(simple).append('(')
                .append(OWNER).append('<').append(sourceName(schema.componentSubject()))
                .append("> owner){provider=new ").append(owner).append("(owner); }\npublic ").append(owner)
                .append(" provider(){return provider;}\n")
                .append(
                    "public void setRenderTarget(com.elfmcys.ysm.model.resource.client.ModelRenderTarget target){provider.setRenderTarget(target); }\n")
                .append(
                    "public void tick(int previousTick,int currentTick){provider.tick(previousTick,currentTick); }\n")
                .append(
                    "public void update(float previousTime,float currentTime,float partialTicks){provider.update(previousTime,currentTime,partialTicks); }\n")
                .append(
                    "public void beginEval(com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent<?> event){provider.beginEval(event); }\n")
                .append("public void endEval(){provider.endEval(); }\n");
        }
        for (Member m : schema.members()) {
            String result = sourceName(m.result().carrier());
            out.append("public ").append(m.isStatic() ? "static " : "").append(result).append(' ').append(m.adapter())
                .append("(com.elfmcys.ysm.expression.host.MolangContext<").append(sourceName(m.subject()))
                .append("> context");
            if (!m.shape().equals("FIXED"))
                out.append(",int argc");
            for (int i = 0; i < m.fixed().size(); i++)
                out.append(',').append(sourceName(m.fixed().get(i).carrier())).append(" a").append(i);
            if (m.tail() != null)
                out.append(',').append(sourceName(m.tail().carrier())).append("[] tail");
            out.append("){ ");
            String call = (m.isStatic() ? owner : "provider") + "." + m.javaName() + "(context"
                + (!m.shape().equals("FIXED") ? ",argc" : "");
            for (int i = 0; i < m.fixed().size(); i++)
                call += ",a" + i;
            if (m.tail() != null)
                call += ",tail";
            call += ")";
            if (m.result().kind().equals("VOID"))
                out.append(call).append(';');
            else
                out.append("return ").append(call).append(';');
            out.append("}\n");
        }
        for (Value value : values)
            if (value.composite())
                out.append(valueHelpers(value));
        return out.append("}\n").toString();
    }

    private String valueHelpers(Value value) {
        String name = value.prefix(), type = sourceName(value.carrier());
        StringBuilder out = new StringBuilder();
        if (value.array()) {
            boolean floats = value.kind().equals("FLOAT_LIST");
            out.append("public static int ").append(name).append("Length(").append(type)
                .append(" value){return value.size();}\npublic static ").append(expressionCarrier(value.element()))
                .append(' ').append(name).append("Read(").append(type).append(" value,int index){return ")
                .append(floats ? "value.getFloat(index)" : unbox(value.element(), "value.get(index)")).append(";}\n")
                .append("public static ").append(type).append(' ').append(name).append("Zero(){return ")
                .append(floats ? "it.unimi.dsi.fastutil.floats.FloatLists.EMPTY_LIST" : "java.util.List.of()")
                .append(";}\npublic static boolean ").append(name).append("Equal(").append(type).append(" left,")
                .append(type)
                .append(" right){if(left.size()!=right.size())return false;for(int i=0;i<left.size();i++)if(!(")
                .append(equal(value.element(), floats ? "left.getFloat(i)" : unbox(value.element(), "left.get(i)"),
                    floats ? "right.getFloat(i)" : unbox(value.element(), "right.get(i)")))
                .append("))return false;return true;}\n");
        } else {
            for (var f : value.fields())
                out.append("public static ").append(expressionCarrier(f.value())).append(' ').append(name)
                    .append("Read_").append(f.name()).append('(').append(type).append(" value){return value.")
                    .append(f.name()).append("();}\n");
            out.append("public static boolean ").append(name).append("Equal(").append(type).append(" left,")
                .append(type).append(" right){return ")
                .append(value.fields().stream()
                    .map(f -> equal(f.value(), "left." + f.name() + "()", "right." + f.name() + "()"))
                    .reduce((a, b) -> a + "&&" + b).orElse("true"))
                .append(";}\n");
        }
        return out.toString();
    }

    private static String equal(Value v, String a, String b) {
        if (v.composite())
            return v.prefix() + "Equal(" + a + "," + b + ")";
        if (v.kind().equals("STRING"))
            return a + ".equals(" + b + ")";
        if (v.kind().equals("INT"))
            return "((float)(" + a + ")== (float)(" + b + "))";
        return "(" + a + "==" + b + ")";
    }

    private static String expressionCarrier(Value v) {
        return switch (v.kind()) {
            case "INT", "FLOAT" -> "float";
            case "BOOLEAN" -> "boolean";
            default -> sourceName(v.carrier());
        };
    }

    private static String unbox(Value v, String expression) {
        return switch (v.kind()) {
            case "FLOAT" -> "((java.lang.Float)java.util.Objects.requireNonNull(" + expression + ")).floatValue()";
            case "INT" -> "((java.lang.Integer)java.util.Objects.requireNonNull(" + expression + ")).intValue()";
            case "BOOLEAN" ->
                "((java.lang.Boolean)java.util.Objects.requireNonNull(" + expression + ")).booleanValue()";
            default -> "((" + sourceName(v.carrier()) + ")java.util.Objects.requireNonNull(" + expression + "))";
        };
    }

    private String descriptor(TypeMirror mirror) {
        return switch (mirror.getKind()) {
            case VOID -> "V";
            case BOOLEAN -> "Z";
            case INT -> "I";
            case FLOAT -> "F";
            case ARRAY -> "[" + descriptor(((ArrayType)mirror).getComponentType());
            case DECLARED -> "L" + binary((TypeElement)((DeclaredType)mirror).asElement()).replace('.', '/') + ";";
            default -> throw invalid("open or unsupported Java descriptor " + mirror);
        };
    }

    private AnnotationMirror annotation(Element element, String name) {
        return element.getAnnotationMirrors().stream().filter(a -> a.getAnnotationType().toString().equals(name))
            .findFirst().orElse(null);
    }

    private Map<String, AnnotationValue> values(AnnotationMirror annotation) {
        var out = new HashMap<String, AnnotationValue>();
        if (annotation != null)
            processingEnv.getElementUtils().getElementValuesWithDefaults(annotation)
                .forEach((key, value) -> out.put(key.getSimpleName().toString(), value));
        return out;
    }

    private static String text(Map<String, AnnotationValue> values, String key) {
        return values.containsKey(key) ? values.get(key).getValue().toString() : "";
    }

    private static String enumText(Map<String, AnnotationValue> values, String key) {
        return text(values, key);
    }

    private static int integer(Map<String, AnnotationValue> values, String key) {
        return values.containsKey(key) ? (Integer)values.get(key).getValue() : -1;
    }

    private static String recordField(String name) {
        identifier(name.toLowerCase(Locale.ROOT));
        return name;
    }

    private static String schemaEncoding(DeclarationSchema schema) {
        String encoded = Base64.getEncoder().encodeToString(schema.encode());
        var chunks = new ArrayList<String>();
        for (int start = 0; start < encoded.length(); start += 16_384)
            chunks.add(quote(encoded.substring(start, Math.min(start + 16_384, encoded.length()))));
        return "String.join(\"\",new String[]{" + String.join(",", chunks) + "})";
    }

    private static String identifier(String name) {
        if (!name.matches("[a-z_][a-z0-9_]*"))
            throw invalid("invalid canonical name " + name);
        return name;
    }

    private String binary(TypeElement type) {
        return processingEnv.getElementUtils().getBinaryName(type).toString();
    }

    private static String sourceName(String name) {
        return name.replace('$', '.');
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private static IllegalArgumentException invalid(String text) {
        return new IllegalArgumentException(text);
    }

    private static void visible(TypeElement type) {
        for (Element e = type; e instanceof TypeElement; e = e.getEnclosingElement())
            if (!e.getModifiers().contains(Modifier.PUBLIC))
                throw invalid("owner/value must be publicly visible");
    }
}
