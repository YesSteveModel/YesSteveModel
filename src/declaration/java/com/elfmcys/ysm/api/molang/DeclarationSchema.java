package com.elfmcys.ysm.api.molang;

import com.elfmcys.ysm.compatibility.AbiVersion;

import java.io.*;
import java.util.*;

/** Safe processor output. Reading it never resolves a Java implementation or subject. */
public record DeclarationSchema(String owner, String helper, String namespace, String modId, boolean builtin,
    boolean manual, String componentSubject, List<Dependency> dependencies, List<Member> members) {

    public static final String VERSION = AbiVersion.CACHE_ANIMATION;
    private static final String FORMAT = "YSM-MOLANG-CALLABLE";
    public static final String RESOURCE_ROOT = "META-INF/ysm/molang/";
    public DeclarationSchema {
        dependencies = List.copyOf(dependencies);
        members = List.copyOf(members);
        Objects.requireNonNull(owner);
        Objects.requireNonNull(helper);
        Objects.requireNonNull(namespace);
        Objects.requireNonNull(modId);
        if (!owner.matches("[A-Za-z_$][A-Za-z0-9_$.]*") || !helper.equals(owner + "MolangBindings")
            || !validNamespace(namespace) || !modId.isEmpty() && !modId.matches("[a-z][a-z0-9_]*")
            || builtin && !modId.isEmpty())
            throw new IllegalArgumentException("invalid declaration identity");
        if (!builtin && reserved(namespace))
            throw new IllegalArgumentException("reserved public namespace " + namespace);
        Set<String> ids = new HashSet<>();
        for (Dependency dependency : dependencies) {
            if (!dependency.modId().matches("[a-z][a-z0-9_]*") || !ids.add(dependency.modId()))
                throw new IllegalArgumentException("invalid or duplicate dependency " + dependency.modId());
        }
        Map<String, List<Member>> groups = new LinkedHashMap<>();
        Set<String> adapters = new HashSet<>();
        if (members.isEmpty())
            throw new IllegalArgumentException("declaration has no members");
        for (Member member : members) {
            if (!validNamespace(member.namespace()) || !member.name().matches("[a-z_][a-z0-9_]*")
                || member.name().length() > 128 || !builtin && reserved(member.namespace())
                || !member.adapter().matches("m[0-9]+") || !member.javaName().matches("[A-Za-z_$][A-Za-z0-9_$]*"))
                throw new IllegalArgumentException("invalid member identity");
            if (!adapters.add(member.adapter()))
                throw new IllegalArgumentException("duplicate adapter " + member.adapter());
            groups.computeIfAbsent(member.symbol(), ignored -> new ArrayList<>()).add(member);
            if (!member.isStatic() && !member.subject().equals(componentSubject))
                throw new IllegalArgumentException("member context must exactly match EntityComponent<E>");
            if (!Set.of("CONTEXT", "ACTION").contains(member.effect()))
                throw new IllegalArgumentException("invalid declaration effect");
            if (!member.contextFreeTarget().isEmpty()
                && (!member.contextFreeTarget().matches("[A-Za-z_$][A-Za-z0-9_$]*") || !member.isStatic()
                    || !member.effect().equals("CONTEXT") || !member.shape().equals("FIXED")
                    || !primitiveScalar(member.result()) || member.fixed().stream().anyMatch(v -> !primitiveScalar(v))))
                throw new IllegalArgumentException("invalid context-free target declaration");
            if (member.minimum() < 0 || member.maximum() > 255 || member.minimum() > member.maximum()
                || member.defaults().size() > member.fixed().size()
                || member.tail() != null && (!member.defaults().isEmpty() || member.minimum() < member.fixed().size())
                || member.tail() == null && (member.maximum() != member.fixed().size()
                    || member.minimum() != member.fixed().size() - member.defaults().size()))
                throw new IllegalArgumentException("contradictory or unbounded declaration arity");
            for (int i = 0; i < member.defaults().size(); i++)
                literal(member.fixed().get(member.fixed().size() - member.defaults().size() + i),
                    member.defaults().get(i));
            for (Value value : member.fixed())
                value.validateParameter();
            if (member.tail() != null)
                member.tail().validateParameter();
            member.result().validateResult();
            if (member.nullableRecord() && !member.result().kind().equals("RECORD"))
                throw new IllegalArgumentException("MolangNullable only applies to a record return");
        }
        for (List<Member> group : groups.values()) {
            Member first = group.get(0);
            Set<String> signatures = new HashSet<>();
            for (Member member : group) {
                if (group.size() > 1
                    && (first.isStatic() != member.isStatic() || !first.subject().equals(member.subject())
                        || !first.effect().equals(member.effect())
                        || !first.result().logicalIdentity().equals(member.result().logicalIdentity())
                        || first.mayReturnMissing() != member.mayReturnMissing()))
                    throw new IllegalArgumentException("inconsistent overload group " + member.symbol());
                String signature = member.fixed().stream().map(Value::logicalIdentity).toList() + "/"
                    + (member.tail() == null ? "fixed" : member.tail().logicalIdentity());
                if (!signatures.add(signature))
                    throw new IllegalArgumentException("duplicate normalized signature " + member.symbol());
            }
        }
    }

    private static boolean primitiveScalar(Value v) {
        return Set.of("float", "int", "boolean").contains(v.carrier());
    }

    private static boolean validNamespace(String namespace) {
        return namespace.matches("[a-z][a-z0-9_]*") && namespace.length() <= 64;
    }

    private static boolean reserved(String namespace) {
        return Set.of("ysm", "math", "query", "ctrl", "tlm", "fn", "v", "c", "t").contains(namespace);
    }

    public record Dependency(String modId, String version) {
    }

    public record Field(String name, Value value) {
    }

    public record Value(String kind, String carrier, String prefix, Value element, List<Field> fields) {
        public Value {
            fields = List.copyOf(fields);
        }

        public boolean composite() {
            return kind.equals("RECORD") || array();
        }

        public boolean array() {
            return kind.equals("LIST") || kind.equals("FLOAT_LIST");
        }

        public boolean boxed() {
            return Set.of("java.lang.Float", "java.lang.Integer", "java.lang.Boolean").contains(carrier);
        }

        public String logicalIdentity() {
            return switch (kind) {
                case "INT", "FLOAT" -> "F32";
                case "BOOLEAN", "STRING", "VOID" -> kind;
                default -> identity();
            };
        }

        public String identity() {
            return kind + ":" + carrier + (element == null ? "" : "<" + element.identity() + ">")
                + fields.stream().map(f -> ";" + f.name() + "=" + f.value().identity()).reduce("", String::concat);
        }

        void validateParameter() {
            validateScalar(false);
        }

        private void validateScalar(boolean result) {
            String expected = switch (kind) {
                case "FLOAT" -> result && boxed() ? "java.lang.Float" : "float";
                case "INT" -> result && boxed() ? "java.lang.Integer" : "int";
                case "BOOLEAN" -> result && boxed() ? "java.lang.Boolean" : "boolean";
                case "STRING" -> "java.lang.String";
                case "VOID" -> result ? "void" : "";
                default -> "";
            };
            if (!carrier.equals(expected) || element != null || !fields.isEmpty())
                throw new IllegalArgumentException("unsupported scalar carrier " + carrier);
        }

        void validateResult() {
            if (kind.equals("RECORD")) {
                validateRecord();
            } else if (array()) {
                String expected =
                    kind.equals("FLOAT_LIST") ? "it.unimi.dsi.fastutil.floats.FloatList" : "java.util.List";
                if (!carrier.equals(expected) || element == null || !fields.isEmpty())
                    throw new IllegalArgumentException("invalid list recipe");
                if (kind.equals("FLOAT_LIST")) {
                    element.validateScalar(false);
                    if (!element.kind().equals("FLOAT"))
                        throw new IllegalArgumentException("FloatList requires F32");
                } else if (element.kind().equals("RECORD")) {
                    element.validateRecord();
                } else if (element.kind().equals("STRING")) {
                    element.validateScalar(false);
                } else {
                    throw new IllegalArgumentException("List requires String or a flat record");
                }
            } else {
                validateScalar(true);
            }
        }

        private void validateRecord() {
            if (!kind.equals("RECORD") || element != null || !carrier.matches("[A-Za-z_$][A-Za-z0-9_$.]*"))
                throw new IllegalArgumentException("invalid value recipe shape");
            Set<String> names = new HashSet<>();
            for (Field field : fields) {
                if (!field.name().matches("[A-Za-z_][A-Za-z0-9_]*")
                    || !names.add(field.name().toLowerCase(Locale.ROOT)))
                    throw new IllegalArgumentException("invalid record field " + field.name());
                field.value().validateScalar(false);
            }
        }
    }

    public record Member(String namespace, String name, String javaName, String descriptor, String adapter,
        String subject, boolean isStatic, String contextFreeTarget, String effect, int minimum, int maximum, List<Value> fixed,
        List<String> defaults, Value tail, Value result, boolean nullableRecord) {
        public Member {
            fixed = List.copyOf(fixed);
            defaults = List.copyOf(defaults);
        }

        public String symbol() {
            return namespace + ":" + name;
        }

        public String shape() {
            return tail != null ? "VARIADIC" : defaults.isEmpty() ? "FIXED" : "OPTIONAL";
        }

        public boolean mayReturnMissing() {
            return nullableRecord || result.boxed() || result.kind().equals("FLOAT");
        }
    }

    public static Object literal(Value value, String text) {
        return com.elfmcys.ysm.api.internal.MolangDefaultValues.parse(value.kind(), text);
    }

    public byte[] encode() {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeUTF(FORMAT);
            out.writeUTF(VERSION);
            out.writeUTF(owner);
            out.writeUTF(helper);
            out.writeUTF(namespace);
            out.writeUTF(modId);
            out.writeBoolean(builtin);
            out.writeBoolean(manual);
            out.writeUTF(componentSubject);
            out.writeInt(dependencies.size());
            for (var d : dependencies) {
                out.writeUTF(d.modId());
                out.writeUTF(d.version());
            }
            out.writeInt(members.size());
            for (var m : members) {
                out.writeUTF(m.namespace());
                out.writeUTF(m.name());
                out.writeUTF(m.javaName());
                out.writeUTF(m.descriptor());
                out.writeUTF(m.adapter());
                out.writeUTF(m.subject());
                out.writeBoolean(m.isStatic());
                out.writeUTF(m.contextFreeTarget());
                out.writeUTF(m.effect());
                out.writeInt(m.minimum());
                out.writeInt(m.maximum());
                out.writeInt(m.fixed().size());
                for (var v : m.fixed())
                    writeValue(out, v);
                out.writeInt(m.defaults().size());
                for (var v : m.defaults())
                    out.writeUTF(v);
                out.writeBoolean(m.tail() != null);
                if (m.tail() != null)
                    writeValue(out, m.tail());
                writeValue(out, m.result());
                out.writeBoolean(m.nullableRecord());
            }
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw new IllegalArgumentException("cannot encode declaration", failure);
        }
    }

    public static DeclarationSchema decode(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(1_048_577);
        if (bytes.length > 1_048_576)
            throw new IOException("declaration resource limit exceeded");
        var in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (!in.readUTF().equals(FORMAT))
            throw new IOException("declaration format mismatch");
        if (!in.readUTF().equals(VERSION))
            throw new IOException("declaration version mismatch");
        String owner = in.readUTF(), helper = in.readUTF(), namespace = in.readUTF(), modId = in.readUTF();
        boolean builtin = in.readBoolean(), manual = in.readBoolean();
        String subject = in.readUTF();
        var dependencies = new ArrayList<Dependency>();
        for (int n = count(in, 255); n > 0; n--)
            dependencies.add(new Dependency(in.readUTF(), in.readUTF()));
        var members = new ArrayList<Member>();
        for (int n = count(in, 4096); n > 0; n--) {
            String memberNamespace = in.readUTF(), name = in.readUTF(), method = in.readUTF(),
                descriptor = in.readUTF(), adapter = in.readUTF(), e = in.readUTF();
            boolean stat = in.readBoolean();
            String target = in.readUTF();
            String effect = in.readUTF();
            int min = in.readInt(), max = in.readInt();
            var fixed = new ArrayList<Value>();
            for (int f = count(in, 255); f > 0; f--)
                fixed.add(readValue(in, 1, false));
            var defaults = new ArrayList<String>();
            for (int d = count(in, 255); d > 0; d--)
                defaults.add(in.readUTF());
            Value tail = in.readBoolean() ? readValue(in, 1, false) : null;
            members.add(new Member(memberNamespace, name, method, descriptor, adapter, e, stat, target, effect, min, max,
                fixed, defaults, tail, readValue(in, 1, true), in.readBoolean()));
        }
        if (in.available() != 0)
            throw new IOException("trailing declaration bytes");
        try {
            return new DeclarationSchema(owner, helper, namespace, modId, builtin, manual, subject, dependencies,
                members);
        } catch (IllegalArgumentException failure) {
            throw new IOException("invalid declaration", failure);
        }
    }

    private static int count(DataInputStream in, int maximum) throws IOException {
        int value = in.readInt();
        if (value < 0 || value > maximum)
            throw new IOException("declaration count limit exceeded");
        return value;
    }

    private static void writeValue(DataOutputStream out, Value value) throws IOException {
        out.writeUTF(value.kind());
        out.writeUTF(value.carrier());
        out.writeUTF(value.prefix());
        out.writeBoolean(value.element() != null);
        if (value.element() != null)
            writeValue(out, value.element());
        out.writeInt(value.fields().size());
        for (var field : value.fields()) {
            out.writeUTF(field.name());
            writeValue(out, field.value());
        }
    }

    private static Value readValue(DataInputStream in, int depth, boolean result) throws IOException {
        if (depth > 3)
            throw new IOException("flat schema depth limit exceeded");
        String kind = in.readUTF(), carrier = in.readUTF(), prefix = in.readUTF();
        Value element = in.readBoolean() ? readValue(in, depth + 1, false) : null;
        var fields = new ArrayList<Field>();
        for (int n = count(in, 8192); n > 0; n--)
            fields.add(new Field(in.readUTF(), readValue(in, depth + 1, false)));
        return new Value(kind, carrier, prefix, element, fields);
    }
}
