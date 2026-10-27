package com.elfmcys.ysm.expression.inventory;

import com.elfmcys.ysm.compatibility.AbiVersion;
import com.elfmcys.ysm.info.roulette.forms.ConfigActionRoute;

import com.elfmcys.ysm.expression.EncodedProgramMapper;
import com.elfmcys.ysm.expression.frontend.MolangNames;
import com.elfmcys.ysm.expression.host.ExpressionIdentityHash;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationControllerFileProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationFileProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.BoneKeyFrameProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.ExpressionValueProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.StringDataProto;
import com.elfmcys.ysm.proto.mixel.common.ProgramProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import com.elfmcys.ysm.util.ProtoUtil;
import dev.semaweave.CompilerLimits;
import dev.semaweave.LocalName;
import dev.semaweave.ProgramEntrySignature;
import dev.semaweave.ProgramKey;
import dev.semaweave.ProgramShape;
import dev.semaweave.compilation.DiagnosticLocation;
import dev.semaweave.compilation.ProgramSource;
import dev.semaweave.identity.Fingerprint;
import dev.semaweave.program.EncodedProgram;
import dev.semaweave.program.PortableProgram;
import dev.semaweave.program.PortableProgramCodec;
import dev.semaweave.program.PortableProgramCodecs;
import dev.semaweave.program.PortablePrograms;
import dev.semaweave.program.ProgramNode;
import dev.semaweave.program.ProgramStructuralException;
import dev.semaweave.program.ReadOnlyBytes;
import us.hebi.quickbuf.ProtoMessage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

/** Deterministic, complete inventory of formal model programs for one render target. */
public final class ModelProgramInventory {
    public static final String LOWERING_ABI = AbiVersion.CACHE_ANIMATION;
    private static final byte[] KEY_DOMAIN =
        ("ysm:program-key:" + AbiVersion.CACHE_ANIMATION + "\0").getBytes(StandardCharsets.UTF_8);
    private static final PortableProgramCodec CODEC = PortableProgramCodecs.v1();
    private static final EncodedProgram FALLBACK_F32 = encode(ProgramNode.F32.of(0));
    private static final EncodedProgram FALLBACK_BOOL = encode(new ProgramNode.Bool(false));
    private static final EncodedProgram FALLBACK_UNSET = encode(new ProgramNode.Unset());

    private ModelProgramInventory() {}

    public static Result collect(String targetId, ModelDataProto model, StringDataProto strings, SettingsProto settings)
        throws InventoryException {
        return collect(targetId, model, strings, settings, MolangEventCatalog.production());
    }

    public static Result collect(String targetId, ModelDataProto model, StringDataProto strings, SettingsProto settings,
        MolangEventCatalog eventCatalog) throws InventoryException {
        return collect(targetId, ExpressionSet.MAIN, model, strings, settings, eventCatalog);
    }

    public static Result collect(String targetId, ExpressionSet set, ModelDataProto model, StringDataProto strings,
        SettingsProto settings, MolangEventCatalog eventCatalog) throws InventoryException {
        return prepare(targetId, set, model, strings, settings, eventCatalog).collect();
    }

    /** Traverses formal locations and keys without decoding Portable Program payloads. */
    public static List<LocatedProgram> locate(String targetId, ModelDataProto model, StringDataProto strings,
        SettingsProto settings) throws InventoryException {
        return locate(targetId, model, strings, settings, MolangEventCatalog.production());
    }

    public static List<LocatedProgram> locate(String targetId, ModelDataProto model, StringDataProto strings,
        SettingsProto settings, MolangEventCatalog eventCatalog) throws InventoryException {
        return locate(targetId, ExpressionSet.MAIN, model, strings, settings, eventCatalog);
    }

    public static List<LocatedProgram> locate(String targetId, ExpressionSet set, ModelDataProto model,
        StringDataProto strings, SettingsProto settings, MolangEventCatalog eventCatalog) throws InventoryException {
        return prepare(targetId, set, model, strings, settings, eventCatalog).locations();
    }

    /** One formal traversal supplies warm binding, source identity and cold decoding. */
    public static Prepared prepare(String targetId, ExpressionSet set, ModelDataProto model, StringDataProto strings,
        SettingsProto settings, MolangEventCatalog eventCatalog) throws InventoryException {
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(model, "model");
        var collector = new Collector(targetId, set, eventCatalog);
        collector.indexBorrowedFunctions(strings);
        collector.model(model);
        if (strings != null) {
            collector.strings(strings);
        }
        if (settings != null && set == ExpressionSet.MAIN) {
            collector.settings(settings);
        }
        return collector.finish(model, strings, settings);
    }

    public static final class Prepared {
        private final Collector collector;
        private final List<LocatedCandidate> candidates;
        private final List<LocatedProgram> locations;
        private final ModelDataProto model;
        private final StringDataProto strings;
        private final SettingsProto settings;

        private Prepared(Collector collector, List<LocatedCandidate> candidates, ModelDataProto model,
            StringDataProto strings, SettingsProto settings) {
            this.collector = collector;
            this.candidates = List.copyOf(candidates);
            this.locations = candidates.stream().map(LocatedCandidate::location).toList();
            this.model = model;
            this.strings = strings;
            this.settings = settings;
        }

        public List<LocatedProgram> locations() {
            return locations;
        }

        public Fingerprint sourceFingerprint() throws IOException {
            return ModelProgramInventory.sourceFingerprint(model, strings, settings);
        }

        public Result collect() throws InventoryException {
            var pending = new ArrayList<Pending>(candidates.size());
            var roamingSlots = new TreeMap<LocalName, String>(Comparator.comparing(LocalName::name));
            for (var entry : candidates) {
                var candidate = entry.candidate();
                var facts = candidate.facts();
                var location = entry.location();
                roamingSlots.putAll(facts.roamingSlots());
                var source =
                    new ProgramSource(location.key(), facts.encoded(), facts.signature(), Optional.of(facts.fallback()),
                        new DiagnosticLocation(collector.targetId + '/' + candidate.location()),
                        candidate.role().entryPermissions());
                pending.add(new Pending(source, location.location(),
                    manifest(collector.targetId, candidate.role(), candidate.location(), candidate.discriminator(),
                        location.key(), facts.signature(), facts.fallback(), facts.referencedSymbols(),
                        candidate.eventRoute(), candidate.eventOrder())));
            }
            return new Result(pending.stream().map(Pending::source).toList(),
                pending.stream().map(value -> new ProgramBinding(value.location(), value.source())).toList(),
                pending.stream().map(Pending::manifest).toList(), collector.rejectedSuffixes, roamingSlots,
                collector.eventCatalog.fingerprint(),
                new Counts(collector.literals + collector.sourceCandidates, pending.size(), collector.literals,
                    pending.size(), pending.size(), 0, collector.callableEntries, collector.eventEntries,
                    collector.rejectedSuffixes.size()));
        }
    }

    private record ProgramFacts(EncodedProgram encoded, ProgramEntrySignature signature, EncodedProgram fallback,
        Map<LocalName, String> roamingSlots, List<String> referencedSymbols) {
    }

    private record Candidate(ProgramProto proto, ProgramRole role, String location, String discriminator,
        String callable, MolangEventCatalog.Route eventRoute, int eventOrder, Map<String, String> functions) {
        private ProgramFacts facts() throws InventoryException {
            try {
                var input = EncodedProgramMapper.decode(proto, CODEC);
                var encoded = input.encoded();
                var program = input.program();
                if (eventRoute != null || !functions.isEmpty()) {
                    program = deriveProgram(program, eventRoute == null ? null : eventRoute.role(), functions);
                    encoded = CODEC.encode(program);
                }
                var roaming = new TreeMap<LocalName, String>(Comparator.comparing(LocalName::name));
                collectRoamingSlots(program, roaming);
                return new ProgramFacts(encoded, role.signature(callable, localNames(program)), fallback(role),
                    Map.copyOf(roaming), referencedSymbols(program));
            } catch (ProgramStructuralException | IllegalArgumentException failure) {
                throw new InventoryException("invalid EncodedProgram at " + location, failure);
            }
        }
    }

    private record LocatedCandidate(Candidate candidate, LocatedProgram location) {
    }

    public record Counts(int candidates, int executables, int literals, int programs, int fallbacks, int failures,
        int callableEntries, int eventEntries, int rejectedSuffixes) {
    }

    public record ManifestEntry(String location, ProgramRole role, String classifier, String keyHex, String keySha256,
        String signature, String fallback, List<String> referencedSymbols, String loweringResult, String entryKind,
        String routeId, String payload, String result, int maximumArguments, String handlerSelection, int handlerOrder,
        String ordering, String fallbackPolicy, String effectPolicy, String diagnosticPolicy,
        String publicReachability) {
        public ManifestEntry {
            referencedSymbols = List.copyOf(referencedSymbols);
        }
    }

    public record Result(List<ProgramSource> programs, List<ProgramBinding> bindings, List<ManifestEntry> manifest,
        List<RejectedSuffix> rejectedSuffixes, SortedMap<LocalName, String> roamingSlots,
        dev.semaweave.identity.Fingerprint eventCatalogFingerprint, Counts counts) {
        public Result {
            programs = List.copyOf(programs);
            bindings = List.copyOf(bindings);
            manifest = List.copyOf(manifest);
            rejectedSuffixes = List.copyOf(rejectedSuffixes);
            var slots = new TreeMap<LocalName, String>(Comparator.comparing(LocalName::name));
            slots.putAll(roamingSlots);
            roamingSlots = java.util.Collections.unmodifiableSortedMap(slots);
            Objects.requireNonNull(eventCatalogFingerprint, "eventCatalogFingerprint");
        }
    }

    public record RejectedSuffix(String location, String suffix, String diagnostic) {
        public RejectedSuffix {
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(suffix, "suffix");
            Objects.requireNonNull(diagnostic, "diagnostic");
        }
    }

    public record SemanticLocation(String targetId, ExpressionSet set, String location, ProgramRole role,
        String discriminator) {
        public SemanticLocation(String targetId, String location, ProgramRole role, String discriminator) {
            this(targetId, ExpressionSet.MAIN, location, role, discriminator);
        }

        public SemanticLocation {
            Objects.requireNonNull(set, "set");
            if (Objects.requireNonNull(targetId, "targetId").isBlank()) {
                throw new IllegalArgumentException("targetId must not be blank");
            }
            if (Objects.requireNonNull(location, "location").isBlank()) {
                throw new IllegalArgumentException("location must not be blank");
            }
            Objects.requireNonNull(role, "role");
            discriminator = Objects.requireNonNullElse(discriminator, "");
        }
    }

    public record ProgramBinding(SemanticLocation location, ProgramSource source) {
        public ProgramBinding {
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(source, "source");
        }
    }

    public record LocatedProgram(SemanticLocation location, ProgramKey key, ProgramShape shape) {
        public LocatedProgram {
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(shape, "shape");
        }
    }

    public static final class InventoryException extends Exception {
        public InventoryException(String message) {
            super(message);
        }

        public InventoryException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class Collector {
        private final String targetId;
        private final ExpressionSet set;
        private final MolangEventCatalog eventCatalog;
        private final ArrayList<Candidate> candidates = new ArrayList<>();
        private final ArrayList<RejectedSuffix> rejectedSuffixes = new ArrayList<>();
        private final HashMap<String, Integer> eventOrders = new HashMap<>();
        private final Map<String, Map<String, String>> borrowedFunctions = new HashMap<>();
        private int literals;
        private int sourceCandidates;
        private int callableEntries;
        private int eventEntries;

        private Collector(String targetId, ExpressionSet set, MolangEventCatalog eventCatalog) {
            if (targetId.isBlank()) {
                throw new IllegalArgumentException("targetId must not be blank");
            }
            this.targetId = targetId;
            this.set = Objects.requireNonNull(set, "set");
            this.eventCatalog = Objects.requireNonNull(eventCatalog, "eventCatalog");
        }

        private void model(ModelDataProto model) throws InventoryException {
            var animationFiles = new ArrayList<>(model.animationFiles().object2ObjectEntrySet());
            animationFiles.sort(Comparator.comparing(java.util.Map.Entry::getKey));
            for (var file : animationFiles) {
                animations(file.getValue(), "animation/" + file.getKey());
            }
            var controllerFiles = new ArrayList<>(model.animationControllers().object2ObjectEntrySet());
            controllerFiles.sort(Comparator.comparing(java.util.Map.Entry::getKey));
            for (var file : controllerFiles) {
                controllers(file.getValue(), "controller/" + file.getKey());
            }
        }

        private void indexBorrowedFunctions(StringDataProto strings) {
            if (strings != null) {
                strings.userFunctions().forEach(function -> {
                    var origin = borrowedOrigin(function.name());
                    if (origin.isEmpty())
                        return;
                    var name = function.name().substring(origin.length());
                    var separator = name.indexOf('@');
                    if (separator >= 0)
                        name = name.substring(0, separator);
                    borrowedFunctions.computeIfAbsent(origin, ignored -> new HashMap<>())
                        .put(MolangNames.userFunctionSymbol(name), MolangNames.userFunctionSymbol(origin + name));
                });
            }
            borrowedFunctions.replaceAll((origin, functions) -> Map.copyOf(functions));
        }

        private void animations(AnimationFileProto file, String domain) throws InventoryException {
            for (var animation : file.animations()) {
                animation(animation, domain);
            }
        }

        private void animation(AnimationProto animation, String domain) throws InventoryException {
            var base = domain + '/' + animation.name();
            if (animation.blendWeight().isPresent()) {
                expression(animation.blendWeight().orElseThrow(), base + "/blend-weight");
            }
            for (var bone : animation.boneAnimations()) {
                frames(bone.rotation(), base + "/bone/" + bone.boneName() + "/rotation");
                frames(bone.position(), base + "/bone/" + bone.boneName() + "/position");
                frames(bone.scale(), base + "/bone/" + bone.boneName() + "/scale");
            }
            for (var frameIndex = 0; frameIndex < animation.instructionKeyframes().size(); frameIndex++) {
                var frame = animation.instructionKeyframes().get(frameIndex);
                add(frame.programs(), ProgramRole.ANIMATION_INSTRUCTION, base + "/instruction/" + frameIndex, "", null);
            }
        }

        private void frames(Iterable<BoneKeyFrameProto> frames, String location) throws InventoryException {
            var frameIndex = 0;
            for (var frame : frames) {
                expressions(frame.pre(), location + '/' + frameIndex + "/pre");
                expressions(frame.post(), location + '/' + frameIndex + "/post");
                frameIndex++;
            }
        }

        private void expressions(Iterable<ExpressionValueProto> values, String location) throws InventoryException {
            var index = 0;
            for (var value : values) {
                expression(value, location + '/' + index++);
            }
        }

        private void expression(ExpressionValueProto value, String location) throws InventoryException {
            if (value.hasNum()) {
                literals++;
            } else if (value.hasProgram()) {
                add(value.program(), ProgramRole.ANIMATION_SAMPLE, location, "", null);
            } else {
                throw new InventoryException("missing animation expression value at " + location);
            }
        }

        private void controllers(AnimationControllerFileProto file, String domain) throws InventoryException {
            for (var controller : file.controllers()) {
                for (var state : controller.states()) {
                    var base = domain + '/' + controller.name() + "/state/" + state.name();
                    for (var index = 0; index < state.animations().size(); index++) {
                        var value = state.animations().get(index);
                        add(value.condition(), ProgramRole.CONTROLLER_ANIMATION, base + "/animation",
                            value.name() + ':' + index, null);
                    }
                    for (var index = 0; index < state.transitions().size(); index++) {
                        var value = state.transitions().get(index);
                        add(value.condition(), ProgramRole.CONTROLLER_TRANSITION, base + "/transition",
                            value.dst() + ':' + index, null);
                    }
                    if (state.hasOnEntry()) {
                        add(state.onEntryUnsafe(), ProgramRole.CONTROLLER_ON_ENTRY, base + "/on-entry", "", null);
                    }
                    if (state.hasOnExit()) {
                        add(state.onExitUnsafe(), ProgramRole.CONTROLLER_ON_EXIT, base + "/on-exit", "", null);
                    }
                }
            }
        }

        private void strings(StringDataProto strings) throws InventoryException {
            for (var function : strings.userFunctions()) {
                var name = function.name();
                var separator = name.indexOf('@');
                sourceCandidates++;
                if (separator != 0) {
                    var callableName = separator < 0 ? name : name.substring(0, separator);
                    add(function.body(), ProgramRole.USER_FUNCTION, "function/" + name, "callable",
                        MolangNames.userFunctionSymbol(callableName), null, -1, false);
                    callableEntries++;
                }
                if (separator >= 0 && separator + 1 < name.length()) {
                    var suffix = name.substring(separator + 1);
                    var normalizedSuffix = suffix.toLowerCase(java.util.Locale.ROOT);
                    var order = eventOrders.merge(normalizedSuffix, 1, Integer::sum) - 1;
                    var classification = eventCatalog.classify(normalizedSuffix, order);
                    if (classification instanceof MolangEventCatalog.Classification.Declared declared) {
                        var route = declared.route();
                        if (set == ExpressionSet.FP_ARM && route.role() != ProgramRole.EVENT_DEFER
                            && route.role() != ProgramRole.EVENT_CODED_CONTROLLER)
                            continue;
                        add(function.body(), route.role(), "function/" + name, normalizedSuffix + ':' + order, null,
                            route, order, false);
                        eventEntries++;
                    } else {
                        var rejected = (MolangEventCatalog.Classification.Rejected)classification;
                        rejectedSuffixes.add(new RejectedSuffix(targetId + "/function/" + name, rejected.suffix(),
                            rejected.diagnostic()));
                    }
                } else if (separator >= 0) {
                    rejectedSuffixes
                        .add(new RejectedSuffix(targetId + "/function/" + name, "", "unknown-event-suffix:v2:"));
                }
            }
        }

        private void settings(SettingsProto settings) throws InventoryException {
            for (var buttonIndex = 0; buttonIndex < settings.extraAnimationButtons().size(); buttonIndex++) {
                var button = settings.extraAnimationButtons().get(buttonIndex);
                for (var formIndex = 0; formIndex < button.configForms().size(); formIndex++) {
                    var form = button.configForms().get(formIndex);
                    var route = new ConfigActionRoute(button.id(), formIndex);
                    var base = route.location();
                    var bool = form.type().equals("checkbox");
                    var f32 = form.type().equals("range") || form.type().equals("radio");
                    if (!bool && !f32) {
                        throw new InventoryException("unknown config form type at " + base);
                    }
                    add(form.readProgram(), bool ? ProgramRole.CONFIG_READ_BOOL : ProgramRole.CONFIG_READ_F32, base,
                        "read", null);
                    add(form.writeProgram(), bool ? ProgramRole.CONFIG_WRITE_BOOL : ProgramRole.CONFIG_WRITE_F32, base,
                        "write", null);
                    for (var labelIndex = 0; labelIndex < form.labels().size(); labelIndex++) {
                        var label = form.labels().get(labelIndex);
                        add(label.actionProgram(), ProgramRole.CONFIG_LABEL_ACTION, route.labelLocation(),
                            route.labelDiscriminator(label.name(), labelIndex), null);
                    }
                }
            }
        }

        private void programs(Iterable<ProgramProto> programs, ProgramRole role, String location)
            throws InventoryException {
            var index = 0;
            for (var program : programs) {
                add(program, role, location, Integer.toString(index++), null);
            }
        }

        private void add(ProgramProto proto, ProgramRole role, String location, String discriminator, String callable)
            throws InventoryException {
            add(proto, role, location, discriminator, callable, null, -1, true);
        }

        private void add(ProgramProto proto, ProgramRole role, String location, String discriminator, String callable,
            MolangEventCatalog.Route eventRoute, int eventOrder, boolean countCandidate) throws InventoryException {
            if (countCandidate) {
                sourceCandidates++;
            }
            if (!proto.hasBytecode() || !proto.bytecode().hasRemaining()) {
                throw new InventoryException("invalid EncodedProgram at " + location);
            }
            var sourcePath = location.substring(location.indexOf('/') + 1);
            candidates.add(new Candidate(proto, role, location, discriminator, callable, eventRoute, eventOrder,
                borrowedFunctions.getOrDefault(borrowedOrigin(sourcePath), Map.of())));
        }

        private Prepared finish(ModelDataProto model, StringDataProto strings, SettingsProto settings)
            throws InventoryException {
            var located = new ArrayList<LocatedCandidate>(candidates.size());
            for (var candidate : candidates) {
                var location = new SemanticLocation(targetId, set, candidate.location(), candidate.role(),
                    candidate.discriminator());
                located.add(new LocatedCandidate(candidate,
                    new LocatedProgram(location,
                        key(targetId, set, candidate.role(), candidate.location(), candidate.discriminator()),
                        candidate.role().shape())));
            }
            located.sort(Comparator.comparing(value -> value.location().key()));
            var keys = new HashSet<ProgramKey>();
            for (var value : located) {
                if (!keys.add(value.location().key())) {
                    throw new InventoryException("duplicate canonical program key at " + value.candidate().location());
                }
            }
            return new Prepared(this, located, model, strings, settings);
        }

    }

    private record Pending(ProgramSource source, SemanticLocation location, ManifestEntry manifest) {
    }

    public static Fingerprint sourceFingerprint(ModelDataProto model, StringDataProto strings, SettingsProto settings)
        throws IOException {
        var bytes = new ByteArrayOutputStream();
        append(bytes, "ysm:program-input:" + AbiVersion.CACHE_ANIMATION);
        append(bytes, protoFingerprint(
            model.toBuilder().clearGeoModels().clearAnimationFiles().clearAnimationControllers().build()));
        var geometry = new TreeMap<>(model.geoModels());
        number(bytes, geometry.size());
        for (var file : geometry.entrySet()) {
            append(bytes, file.getKey());
            var content = file.getValue().duplicate();
            var value = new byte[content.remaining()];
            content.get(value);
            append(bytes, Fingerprint.compute(ExpressionIdentityHash.SHA_256, ReadOnlyBytes.copyOf(value)));
        }
        var animationFiles = new TreeMap<>(model.animationFiles());
        number(bytes, animationFiles.size());
        for (var file : animationFiles.entrySet()) {
            append(bytes, file.getKey());
            var borrowedFile = !borrowedOrigin(file.getKey()).isEmpty();
            number(bytes, borrowedFile ? 1 : 0);
            if (!borrowedFile) {
                append(bytes, protoFingerprint(file.getValue()));
                continue;
            }
            append(bytes, protoFingerprint(file.getValue().toBuilder().clearAnimations().build()));
            number(bytes, file.getValue().animations().size());
            for (var animation : file.getValue().animations()) {
                append(bytes, protoFingerprint(animation));
            }
        }
        var controllers = new TreeMap<>(model.animationControllers());
        number(bytes, controllers.size());
        for (var file : controllers.entrySet()) {
            append(bytes, file.getKey());
            append(bytes, protoFingerprint(file.getValue()));
        }
        number(bytes, strings == null ? 0 : 1);
        if (strings != null) {
            append(bytes, protoFingerprint(strings.toBuilder().clearUserFunctions().build()));
            number(bytes, strings.userFunctions().size());
            for (var function : strings.userFunctions()) {
                append(bytes, protoFingerprint(function));
            }
        }
        number(bytes, settings == null ? 0 : 1);
        if (settings != null)
            append(bytes, protoFingerprint(settings));
        return Fingerprint.compute(ExpressionIdentityHash.SHA_256, ReadOnlyBytes.copyOf(bytes.toByteArray()));
    }

    private static Fingerprint protoFingerprint(ProtoMessage<?> message) throws IOException {
        return Fingerprint.compute(ExpressionIdentityHash.SHA_256,
            ReadOnlyBytes.copyOf(ProtoUtil.serializeToArray(message)));
    }

    private static void append(ByteArrayOutputStream output, Fingerprint fingerprint) {
        var bytes = fingerprint.value().bytes().toByteArray();
        number(output, bytes.length);
        output.writeBytes(bytes);
    }

    private static void number(ByteArrayOutputStream output, int value) {
        output.write(value >>> 24);
        output.write(value >>> 16);
        output.write(value >>> 8);
        output.write(value);
    }

    private static ManifestEntry manifest(String targetId, ProgramRole role, String location, String discriminator,
        ProgramKey key, ProgramEntrySignature signature, EncodedProgram fallback, List<String> referencedSymbols,
        MolangEventCatalog.Route eventRoute, int eventOrder) {
        var bytes = key.canonicalBytes().toByteArray();
        var callable = role == ProgramRole.USER_FUNCTION;
        return new ManifestEntry(targetId + '/' + location, role, role.fallbackClassifier(),
            HexFormat.of().formatHex(bytes), sha256Hex(bytes), signature.toString(),
            role.fallbackClassifier() + ":format=" + fallback.formatId(), referencedSymbols,
            eventRoute == null ? "encoded-program-v1" : "event-derived-program-v2",
            callable ? "CALLABLE" : eventRoute == null ? "PERSISTENT" : "EVENT",
            eventRoute == null ? "" : eventRoute.routeId(), eventRoute == null ? "" : eventRoute.payload(),
            eventRoute == null ? role.shape().name() : eventRoute.result(),
            eventRoute == null ? 0 : eventRoute.maximumArguments(),
            eventRoute == null ? "" : eventRoute.handlerSelection().name(), eventOrder,
            eventRoute == null ? "" : eventRoute.ordering(),
            eventRoute == null ? role.fallbackClassifier() : eventRoute.fallback(),
            eventRoute == null ? "ROLE_DECLARED" : eventRoute.effect(),
            eventRoute == null ? targetId + '/' + location : eventRoute.diagnostic(),
            callable ? "UNIT_LOCAL" : eventRoute == null ? "HOST_BOUND" : eventRoute.publicReachability().name());
    }

    private static PortableProgram deriveProgram(PortableProgram program, ProgramRole role,
        Map<String, String> functions) throws InventoryException, ProgramStructuralException {
        var roots = new ArrayList<ProgramNode>(program.roots().size());
        for (var root : program.roots()) {
            roots.add(eventNode(root, role, functions));
        }
        return PortablePrograms.ofRoots(roots);
    }

    private static ProgramNode eventNode(ProgramNode node, ProgramRole role, Map<String, String> functions)
        throws InventoryException {
        if (node instanceof ProgramNode.Index index && isArgs(index.target())) {
            var rewrittenIndex = eventNode(index.index(), role, functions);
            if (role == ProgramRole.EVENT_PLAYER_UPDATE) {
                return new ProgramNode.Conditional(
                    new ProgramNode.Binary(ProgramNode.BinaryOperator.EQ, rewrittenIndex, ProgramNode.F32.of(0)),
                    argsSymbol(), new ProgramNode.Unset());
            }
            if (role == ProgramRole.EVENT_SYNC || role == ProgramRole.EVENT_DEFER) {
                return new ProgramNode.Index(argsSymbol(), rewrittenIndex);
            }
        }
        if (isArgs(node) && (role == ProgramRole.EVENT_SYNC || role == ProgramRole.EVENT_DEFER)) {
            throw new InventoryException("event args list view cannot escape indexed access");
        }
        if (node instanceof ProgramNode.Symbol value) {
            var ref = value.ref();
            if (ref.domain() == ProgramNode.SymbolDomain.USER_FUNCTION && functions.containsKey(ref.name()))
                ref = new ProgramNode.SymbolRef(ref.domain(), functions.get(ref.name()));
            return new ProgramNode.Symbol(ref, value.accessForm(), mapEventNodes(value.arguments(), role, functions));
        }
        if (node instanceof ProgramNode.Member value) {
            return new ProgramNode.Member(eventNode(value.target(), role, functions), value.member());
        }
        if (node instanceof ProgramNode.Index value) {
            return new ProgramNode.Index(eventNode(value.target(), role, functions),
                eventNode(value.index(), role, functions));
        }
        if (node instanceof ProgramNode.Unary value) {
            return new ProgramNode.Unary(value.operator(), eventNode(value.operand(), role, functions));
        }
        if (node instanceof ProgramNode.Binary value) {
            return new ProgramNode.Binary(value.operator(), eventNode(value.left(), role, functions),
                eventNode(value.right(), role, functions));
        }
        if (node instanceof ProgramNode.Math value) {
            return new ProgramNode.Math(value.operator(), mapEventNodes(value.arguments(), role, functions));
        }
        if (node instanceof ProgramNode.Coalesce value) {
            return new ProgramNode.Coalesce(eventNode(value.left(), role, functions),
                eventNode(value.right(), role, functions));
        }
        if (node instanceof ProgramNode.Conditional value) {
            return new ProgramNode.Conditional(eventNode(value.condition(), role, functions),
                eventNode(value.ifTrue(), role, functions), eventNode(value.ifFalse(), role, functions));
        }
        if (node instanceof ProgramNode.Assignment value) {
            if (role != null && containsArgsView(value.target())) {
                throw new InventoryException("event args view is immutable");
            }
            return new ProgramNode.Assignment(eventNode(value.target(), role, functions),
                eventNode(value.value(), role, functions));
        }
        if (node instanceof ProgramNode.Block value) {
            return new ProgramNode.Block(mapEventNodes(value.children(), role, functions));
        }
        if (node instanceof ProgramNode.Control value) {
            if (value.operator() == ProgramNode.ControlOperator.RETURN && role != null
                && role.shape() == ProgramShape.VOID && value.value().isPresent()) {
                return new ProgramNode.Block(List.of(eventNode(value.value().orElseThrow(), role, functions),
                    new ProgramNode.Control(value.operator(), Optional.empty())));
            }
            return new ProgramNode.Control(value.operator(), value.value().isEmpty() ? Optional.empty()
                : Optional.of(eventNode(value.value().orElseThrow(), role, functions)));
        }
        if (node instanceof ProgramNode.Loop value) {
            return new ProgramNode.Loop(eventNode(value.count(), role, functions),
                eventNode(value.body(), role, functions));
        }
        if (node instanceof ProgramNode.ForEach value) {
            return new ProgramNode.ForEach(value.local(), eventNode(value.iterable(), role, functions),
                eventNode(value.body(), role, functions));
        }
        if (node instanceof ProgramNode.WithContext value) {
            return new ProgramNode.WithContext(eventNode(value.subject(), role, functions),
                eventNode(value.body(), role, functions));
        }
        return node;
    }

    private static List<ProgramNode> mapEventNodes(List<ProgramNode> nodes, ProgramRole role,
        Map<String, String> functions) throws InventoryException {
        var result = new ArrayList<ProgramNode>(nodes.size());
        for (var node : nodes) {
            result.add(eventNode(node, role, functions));
        }
        return List.copyOf(result);
    }

    private static String borrowedOrigin(String source) {
        if (!source.startsWith("fallback/"))
            return "";
        int position = 0;
        for (int index = 0; index < 4; index++) {
            position = source.indexOf('/', position);
            if (position < 0)
                return "";
            position++;
        }
        return source.substring(0, position);
    }

    private static boolean isArgs(ProgramNode node) {
        return node instanceof ProgramNode.Symbol symbol && symbol.ref().domain() == ProgramNode.SymbolDomain.LOCAL
            && symbol.ref().name().equals("args") && symbol.accessForm() == ProgramNode.AccessForm.READ;
    }

    private static boolean containsArgsView(ProgramNode node) {
        return isArgs(node) || node instanceof ProgramNode.Index index && isArgs(index.target());
    }

    private static ProgramNode.Symbol argsSymbol() {
        return new ProgramNode.Symbol(new ProgramNode.SymbolRef(ProgramNode.SymbolDomain.LOCAL, "args"),
            ProgramNode.AccessForm.READ, List.of());
    }

    private static ProgramKey key(String targetId, ExpressionSet set, ProgramRole role, String location,
        String discriminator) {
        var separator = location.indexOf('/');
        if (separator <= 0 || separator == location.length() - 1) {
            throw new IllegalArgumentException("program location must contain asset domain and structural locator");
        }
        var canonical = new ByteArrayOutputStream();
        canonical.writeBytes(KEY_DOMAIN);
        append(canonical, targetId);
        append(canonical, set.id());
        append(canonical, location.substring(0, separator));
        append(canonical, location.substring(separator + 1));
        append(canonical, role.name());
        append(canonical, discriminator);
        var bytes = canonical.toByteArray();
        var digest = sha256().digest(bytes);
        // Keep structural ordering for bounded analysis; the full digest distinguishes long origins.
        int prefixLength = Math.min(bytes.length, com.elfmcys.ysm.expression.host.ExpressionPolicy.COMPILER_LIMITS.maxProgramKeyBytes() - digest.length);
        var key = java.util.Arrays.copyOf(bytes, prefixLength + digest.length);
        System.arraycopy(digest, 0, key, prefixLength, digest.length);
        return new ProgramKey(ReadOnlyBytes.copyOf(key));
    }

    private static void append(ByteArrayOutputStream output, String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        output.write(bytes.length >>> 24);
        output.write(bytes.length >>> 16);
        output.write(bytes.length >>> 8);
        output.write(bytes.length);
        output.writeBytes(bytes);
    }

    private static EncodedProgram fallback(ProgramRole role) {
        return switch (role.shape()) {
            case F32 -> FALLBACK_F32;
            case BOOLEAN -> FALLBACK_BOOL;
            case USER_FUNCTION -> FALLBACK_F32;
            case VALUE, VOID -> FALLBACK_UNSET;
        };
    }

    private static EncodedProgram encode(ProgramNode node) {
        try {
            return CODEC.encode(PortablePrograms.ofRoots(List.of(node)));
        } catch (ProgramStructuralException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static List<LocalName> localNames(PortableProgram program) {
        var names = new java.util.TreeSet<String>();
        visit(program, node -> {
            if (node instanceof ProgramNode.Symbol symbol && symbol.ref().domain() == ProgramNode.SymbolDomain.LOCAL) {
                names.add(symbol.ref().name());
            } else if (node instanceof ProgramNode.ForEach loop
                && loop.local().domain() == ProgramNode.SymbolDomain.LOCAL) {
                names.add(loop.local().name());
            }
        });
        return names.stream().map(LocalName::new).toList();
    }

    private static void collectRoamingSlots(PortableProgram program, Map<LocalName, String> result) {
        visit(program, node -> {
            if (!(node instanceof ProgramNode.Symbol symbol)
                || symbol.ref().domain() != ProgramNode.SymbolDomain.INSTANCE
                || !com.elfmcys.ysm.api.molang.MolangNames.isReservedRoamingSlot(symbol.ref().name())) {
                return;
            }
            var member = com.elfmcys.ysm.api.molang.MolangNames.roamingMember(symbol.ref().name());
            result.putIfAbsent(new LocalName(symbol.ref().name()), member);
        });
    }

    private static List<String> referencedSymbols(PortableProgram program) {
        var values = new java.util.TreeSet<String>();
        visit(program, node -> {
            if (node instanceof ProgramNode.Symbol symbol) {
                values.add(symbol.ref().domain().name() + ':' + symbol.ref().name() + ':' + symbol.accessForm().name());
            }
        });
        return List.copyOf(values);
    }

    private static void visit(PortableProgram program, java.util.function.Consumer<ProgramNode> consumer) {
        var pending = new ArrayDeque<ProgramNode>();
        pending.addAll(program.roots());
        while (!pending.isEmpty()) {
            var node = pending.removeLast();
            consumer.accept(node);
            if (node instanceof ProgramNode.Symbol value)
                pending.addAll(value.arguments());
            else if (node instanceof ProgramNode.Member value)
                pending.add(value.target());
            else if (node instanceof ProgramNode.Index value) {
                pending.add(value.target());
                pending.add(value.index());
            } else if (node instanceof ProgramNode.Unary value)
                pending.add(value.operand());
            else if (node instanceof ProgramNode.Binary value) {
                pending.add(value.left());
                pending.add(value.right());
            } else if (node instanceof ProgramNode.Math value) {
                pending.addAll(value.arguments());
            } else if (node instanceof ProgramNode.Coalesce value) {
                pending.add(value.left());
                pending.add(value.right());
            } else if (node instanceof ProgramNode.Conditional value) {
                pending.add(value.condition());
                pending.add(value.ifTrue());
                pending.add(value.ifFalse());
            } else if (node instanceof ProgramNode.Assignment value) {
                pending.add(value.target());
                pending.add(value.value());
            } else if (node instanceof ProgramNode.Block value)
                pending.addAll(value.children());
            else if (node instanceof ProgramNode.Control value)
                value.value().ifPresent(pending::add);
            else if (node instanceof ProgramNode.Loop value) {
                pending.add(value.count());
                pending.add(value.body());
            } else if (node instanceof ProgramNode.ForEach value) {
                pending.add(value.iterable());
                pending.add(value.body());
            } else if (node instanceof ProgramNode.WithContext value) {
                pending.add(value.subject());
                pending.add(value.body());
            }
        }
    }

    private static String sha256Hex(byte[] bytes) {
        return HexFormat.of().formatHex(sha256().digest(bytes));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new AssertionError(failure);
        }
    }
}
