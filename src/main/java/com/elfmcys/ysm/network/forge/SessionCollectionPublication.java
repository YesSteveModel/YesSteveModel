package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.model.ModelRuntime;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogAccess;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogRecord;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogTransition;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.HierarchyPath;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelPackDescriptor;
import com.elfmcys.ysm.model.session.client.state.PublicationEntry;
import com.elfmcys.ysm.model.session.client.state.RemotePublicationSnapshot;
import com.elfmcys.ysm.model.session.server.ServerModelSession;
import com.elfmcys.ysm.proto.network.CatalogCollectionOperationProto;
import com.elfmcys.ysm.proto.network.CatalogPublicationProto;
import com.elfmcys.ysm.proto.network.CollectionOperationType;
import com.elfmcys.ysm.proto.network.DefaultAnimationProto;
import com.elfmcys.ysm.proto.network.DefaultAnimationCollectionOperationProto;
import com.elfmcys.ysm.proto.network.GrantCollectionOperationProto;
import com.elfmcys.ysm.proto.network.PackPresentationProto;
import com.elfmcys.ysm.proto.network.PackPresentationCollectionOperationProto;
import com.elfmcys.ysm.proto.network.SessionDeltaFragmentProto;
import com.elfmcys.ysm.proto.network.SessionFullFragmentProto;
import com.elfmcys.ysm.util.ProtoBytes;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Typed model-session collection producer, validator, assembler, and publication candidate. */
final class SessionCollectionPublication {
    static final Limits PROTOCOL_LIMITS = new Limits(65_536, 128L * 1024 * 1024, 16_384, 16_384, 4_096, 4_096);

    private SessionCollectionPublication() {}

    static List<SessionFullFragmentProto> fullFragments(ServerModelSession.AuthoritySnapshot authority,
        long transferId) {
        return fullFragments(authority, transferId, defaultAnimations());
    }

    static List<SessionFullFragmentProto> fullFragments(ServerModelSession.AuthoritySnapshot authority, long transferId,
        Map<String, Hash256> animations) {
        requireTransferId(transferId);
        animations = Map.copyOf(animations);
        var parts = new ArrayList<FullPart>();
        authority.catalog().byModelId().entrySet().stream().sorted(Map.Entry.comparingByKey())
            .forEach(value -> parts.add(FullPart.catalog(CatalogCollectionOperationProto.newBuilder().setOpType(full())
                .addEntries(publication(value.getValue(), authority)).build())));
        if (authority.catalog().byModelId().isEmpty()) {
            parts.add(FullPart.catalog(CatalogCollectionOperationProto.newBuilder().setOpType(full()).build()));
        }
        authority.grants().stream().sorted().forEach(hash -> parts.add(FullPart.grants(
            GrantCollectionOperationProto.newBuilder().setOpType(full()).addModelIds(ProtoBytes.wrap(hash)).build())));
        if (authority.grants().isEmpty()) {
            parts.add(FullPart.grants(GrantCollectionOperationProto.newBuilder().setOpType(full()).build()));
        }
        authority.catalog().packs().stream().sorted((left, right) -> compareUtf8(left.hierarchy(), right.hierarchy()))
            .forEach(value -> parts.add(FullPart.packs(PackPresentationCollectionOperationProto.newBuilder()
                .setOpType(full()).addEntries(pack(value)).build())));
        if (authority.catalog().packs().isEmpty()) {
            parts.add(FullPart.packs(PackPresentationCollectionOperationProto.newBuilder().setOpType(full()).build()));
        }
        animations
            .entrySet().stream().sorted(
                (left, right) -> compareUtf8(left.getKey(), right.getKey()))
            .forEach(
                animation -> parts
                    .add(FullPart.animations(DefaultAnimationCollectionOperationProto
                        .newBuilder().setOpType(full()).addEntries(DefaultAnimationProto.newBuilder()
                            .setName(animation.getKey()).setHash(ProtoBytes.wrap(animation.getValue())).build())
                        .build())));
        if (animations.isEmpty()) {
            parts.add(
                FullPart.animations(DefaultAnimationCollectionOperationProto.newBuilder().setOpType(full()).build()));
        }
        requireOutgoingCounts(parts.size(), authority.catalog().byModelId().size(), authority.grants().size(),
            authority.catalog().packs().size(), animations.size());
        var result = new ArrayList<SessionFullFragmentProto>(parts.size());
        for (var sequence = 0; sequence < parts.size(); sequence++) {
            result.add(parts.get(sequence).message(transferId, sequence, sequence == parts.size() - 1));
        }
        return List.copyOf(result);
    }

    static List<SessionDeltaFragmentProto> deltaFragments(ServerModelSession.CatalogTransition transition,
        long transferId) {
        Objects.requireNonNull(transition, "transition");
        requireTransferId(transferId);
        var previous = transition.previous();
        var current = transition.current();
        var parts = new ArrayList<DeltaPart>();

        previous.catalog().byModelId().keySet().stream()
            .filter(modelId -> !current.catalog().byModelId().containsKey(modelId)).sorted()
            .forEach(modelId -> parts.add(DeltaPart.catalog(CatalogCollectionOperationProto.newBuilder()
                .setOpType(remove()).addModelIds(ProtoBytes.wrap(modelId)).build())));
        current.catalog().byModelId().entrySet().stream().sorted(Map.Entry.comparingByKey()).filter(value -> {
            var before = previous.catalog().byModelId().get(value.getKey());
            return before == null || !entry(before, previous).equals(entry(value.getValue(), current));
        }).forEach(value -> parts.add(DeltaPart.catalog(CatalogCollectionOperationProto.newBuilder().setOpType(add())
            .addEntries(publication(value.getValue(), current)).build())));

        previous.grants().stream().filter(value -> !current.grants().contains(value)).sorted()
            .forEach(value -> parts.add(DeltaPart.grants(GrantCollectionOperationProto.newBuilder().setOpType(remove())
                .addModelIds(ProtoBytes.wrap(value)).build())));
        current.grants().stream().filter(value -> !previous.grants().contains(value)).sorted()
            .forEach(value -> parts.add(DeltaPart.grants(GrantCollectionOperationProto.newBuilder().setOpType(add())
                .addModelIds(ProtoBytes.wrap(value)).build())));

        var beforePacks = packsByHierarchy(previous.catalog().packs());
        var currentPacks = packsByHierarchy(current.catalog().packs());
        beforePacks.keySet().stream().filter(path -> !currentPacks.containsKey(path))
            .sorted(SessionCollectionPublication::compareUtf8)
            .forEach(path -> parts.add(DeltaPart.packs(PackPresentationCollectionOperationProto.newBuilder()
                .setOpType(remove()).addHierarchyPaths(path).build())));
        currentPacks.entrySet().stream().sorted((left, right) -> compareUtf8(left.getKey(), right.getKey()))
            .filter(value -> !samePack(value.getValue(), beforePacks.get(value.getKey())))
            .forEach(value -> parts.add(DeltaPart.packs(PackPresentationCollectionOperationProto.newBuilder()
                .setOpType(add()).addEntries(pack(value.getValue())).build())));
        requireDeltaCounts(parts);
        return wrapDelta(parts, transferId);
    }

    static boolean hasDelta(ServerModelSession.CatalogTransition transition) {
        Objects.requireNonNull(transition, "transition");
        var previous = transition.previous();
        var current = transition.current();
        if (!previous.grants().equals(current.grants())
            || previous.catalog().byModelId().size() != current.catalog().byModelId().size()) {
            return true;
        }
        for (var entry : current.catalog().byModelId().entrySet()) {
            var before = previous.catalog().byModelId().get(entry.getKey());
            if (before == null || !SessionCollectionPublication.entry(before, previous)
                .equals(SessionCollectionPublication.entry(entry.getValue(), current))) {
                return true;
            }
        }
        var beforePacks = packsByHierarchy(previous.catalog().packs());
        var currentPacks = packsByHierarchy(current.catalog().packs());
        if (!beforePacks.keySet().equals(currentPacks.keySet())) {
            return true;
        }
        return currentPacks.entrySet().stream()
            .anyMatch(entry -> !samePack(entry.getValue(), beforePacks.get(entry.getKey())));
    }

    static List<SessionDeltaFragmentProto> authorityDeltaFragments(ServerModelSession.AuthoritySnapshot authority,
        long transferId) {
        requireTransferId(transferId);
        var parts = new ArrayList<DeltaPart>();
        authority.catalog().byModelId().entrySet().stream().sorted(Map.Entry.comparingByKey())
            .forEach(value -> parts.add(DeltaPart.catalog(CatalogCollectionOperationProto.newBuilder().setOpType(add())
                .addEntries(publication(value.getValue(), authority)).build())));
        parts.add(DeltaPart.grants(GrantCollectionOperationProto.newBuilder().setOpType(clear()).build()));
        authority.grants().stream().filter(authority.catalog().byModelId()::containsKey).sorted()
            .forEach(hash -> parts.add(DeltaPart.grants(GrantCollectionOperationProto.newBuilder().setOpType(add())
                .addModelIds(ProtoBytes.wrap(hash)).build())));
        requireDeltaCounts(parts);
        return wrapDelta(parts, transferId);
    }

    static CatalogSnapshot distributableCatalog(CatalogSnapshot catalog) {
        var records = new LinkedHashMap<Hash256, CatalogRecord>();
        catalog.byModelId().forEach((modelId, record) -> {
            if (record.location().rootKind() != CatalogRootKind.BUILTIN
                || !record.location().path().value().equals("default")) {
                records.put(modelId, record);
            }
        });
        return new CatalogSnapshot(records, catalog.packs(), catalog.report());
    }

    static Map<String, Hash256> defaultAnimations() {
        var result = new LinkedHashMap<String, Hash256>();
        ModelRuntime.system().builtinContract().animationEntries().forEach(animation -> result
            .put(animation.key().domain() + "\0" + animation.key().name(), animation.currentPayloadHash()));
        return Map.copyOf(result);
    }

    private static List<SessionDeltaFragmentProto> wrapDelta(List<DeltaPart> parts, long transferId) {
        if (parts.isEmpty()) {
            return List.of();
        }
        var result = new ArrayList<SessionDeltaFragmentProto>(parts.size());
        for (var sequence = 0; sequence < parts.size(); sequence++) {
            result.add(parts.get(sequence).message(transferId, sequence, sequence == parts.size() - 1));
        }
        return List.copyOf(result);
    }

    private static void requireOutgoingCounts(int fragments, int catalog, int grants, int packs, int animations) {
        if (fragments > PROTOCOL_LIMITS.fragments() || catalog > PROTOCOL_LIMITS.catalogRecords()
            || grants > PROTOCOL_LIMITS.grantRecords() || packs > PROTOCOL_LIMITS.packRecords()
            || animations > PROTOCOL_LIMITS.animationRecords()) {
            throw new IllegalArgumentException("Model-session publication exceeds business limits");
        }
    }

    private static void requireDeltaCounts(List<DeltaPart> parts) {
        var counts = new int[CollectionKind.values().length];
        for (var part : parts) {
            counts[part.kind().ordinal()]++;
        }
        requireOutgoingCounts(parts.size(), counts[CollectionKind.CATALOG.ordinal()],
            counts[CollectionKind.GRANTS.ordinal()], counts[CollectionKind.PACKS.ordinal()],
            counts[CollectionKind.ANIMATIONS.ordinal()]);
    }

    static final class Receiver {
        private final Map<String, Hash256> expectedAnimations;
        private final Limits limits;
        private long highWater;
        private Transfer active;

        Receiver(Map<String, Hash256> expectedAnimations) {
            this(expectedAnimations, PROTOCOL_LIMITS);
        }

        Receiver(Map<String, Hash256> expectedAnimations, Limits limits) {
            this.expectedAnimations = Map.copyOf(expectedAnimations);
            this.limits = Objects.requireNonNull(limits, "limits");
        }

        Result acceptFull(SessionFullFragmentProto message, RemotePublicationSnapshot previous) {
            try {
                return accept(true, message.transferId(), message.sequence(), message.finalFragment(),
                    message.getSerializedSize(), parseFull(message), previous);
            } catch (IOException | RuntimeException invalid) {
                active = null;
                return Result.intrinsicInvalid(invalid);
            }
        }

        Result acceptDelta(SessionDeltaFragmentProto message, RemotePublicationSnapshot previous) {
            try {
                return accept(false, message.transferId(), message.sequence(), message.finalFragment(),
                    message.getSerializedSize(), parseDelta(message), previous);
            } catch (IOException | RuntimeException invalid) {
                active = null;
                return Result.intrinsicInvalid(invalid);
            }
        }

        private Result accept(boolean fullTransfer, long transferId, int sequence, boolean finalFragment,
            int decodedBytes, Operation operation, RemotePublicationSnapshot previous) throws IOException {
            requireTransferId(transferId);
            if (sequence < 0 || sequence >= limits.fragments()) {
                throw new IOException("Publication sequence cannot have bounded coverage");
            }
            if (active == null) {
                if (highWater == 0) {
                    if (!fullTransfer || transferId != 1) {
                        throw new IOException("First publication must be full transfer 1");
                    }
                } else if (Long.compareUnsigned(transferId, highWater) <= 0) {
                    throw new IOException("Publication ID is not strictly increasing");
                }
                if (!fullTransfer && previous == null) {
                    throw new IOException("Delta publication has no retained baseline");
                }
                active = new Transfer(transferId, fullTransfer, limits);
                highWater = transferId;
            } else if (active.transferId != transferId || active.fullTransfer != fullTransfer) {
                throw new IOException("Publication transfer overlaps another owner");
            }
            var completed = active.accept(sequence, finalFragment, decodedBytes, operation);
            if (!completed) {
                return Result.pending();
            }
            var transfer = active;
            active = null;
            return fullTransfer ? assembleFull(transfer.orderedOperations(), expectedAnimations)
                : assembleDelta(previous, transfer.orderedOperations(), expectedAnimations);
        }

        void clear() {
            active = null;
        }
    }

    private static Result assembleFull(List<Operation> operations, Map<String, Hash256> expectedAnimations) {
        try {
            validateCanonical(operations, true);
            var candidate = Candidate.empty();
            apply(candidate, operations);
            requireAnimationContract(candidate.animations, expectedAnimations);
            return Result.full(candidate.snapshot());
        } catch (IOException | RuntimeException invalid) {
            return Result.intrinsicInvalid(invalid);
        }
    }

    private static Result assembleDelta(RemotePublicationSnapshot previous, List<Operation> operations,
        Map<String, Hash256> expectedAnimations) {
        try {
            validateCanonical(operations, false);
            var candidate = Candidate.from(previous);
            var missingRemovals = apply(candidate, operations);
            requireAnimationContract(candidate.animations, expectedAnimations);
            try {
                return Result.delta(candidate.snapshot(), missingRemovals);
            } catch (IllegalArgumentException drift) {
                return Result.baselineDrift(drift);
            }
        } catch (IOException | RuntimeException invalid) {
            return Result.intrinsicInvalid(invalid);
        }
    }

    private static Operation parseFull(SessionFullFragmentProto fragment) throws IOException {
        if (fragment.hasCatalog()) {
            return parseCatalog(fragment.catalog(), true);
        } else if (fragment.hasGrants()) {
            return parseGrants(fragment.grants(), true);
        } else if (fragment.hasPackPresentations()) {
            return parsePacks(fragment.packPresentations(), true);
        } else if (fragment.hasDefaultAnimations()) {
            return parseAnimations(fragment.defaultAnimations(), true);
        }
        throw new IOException("Session full fragment has no collection part");
    }

    private static Operation parseDelta(SessionDeltaFragmentProto fragment) throws IOException {
        if (fragment.hasCatalog()) {
            return parseCatalog(fragment.catalog(), false);
        } else if (fragment.hasGrants()) {
            return parseGrants(fragment.grants(), false);
        } else if (fragment.hasPackPresentations()) {
            return parsePacks(fragment.packPresentations(), false);
        } else if (fragment.hasDefaultAnimations()) {
            return parseAnimations(fragment.defaultAnimations(), false);
        }
        throw new IOException("Session delta fragment has no collection part");
    }

    private static Operation parseCatalog(CatalogCollectionOperationProto source, boolean fullTransfer)
        throws IOException {
        var change = change(source.opType(), fullTransfer);
        requirePayload(change, source.entries().size(), source.modelIds().size());
        var entries = new ArrayList<PublicationEntry>(source.entries().size());
        for (var value : source.entries()) {
            entries.add(readPublication(value));
        }
        var hashes = new ArrayList<Hash256>(source.modelIds().size());
        for (var value : source.modelIds()) {
            hashes.add(hash(value, "catalog model id"));
        }
        var keys =
            change == Change.REMOVE ? List.copyOf(hashes) : entries.stream().map(PublicationEntry::modelId).toList();
        requireStrictOrder(keys, Hash256::compareTo, "catalog model ids");
        return new Operation(CollectionKind.CATALOG, change, keys, List.copyOf(entries), List.of(), List.of());
    }

    private static Operation parseGrants(GrantCollectionOperationProto source, boolean fullTransfer)
        throws IOException {
        var change = change(source.opType(), fullTransfer);
        requirePayload(change, source.modelIds().size(), 0);
        var hashes = new ArrayList<Hash256>(source.modelIds().size());
        for (var value : source.modelIds()) {
            hashes.add(hash(value, "grant model id"));
        }
        requireStrictOrder(hashes, Hash256::compareTo, "grant model ids");
        return new Operation(CollectionKind.GRANTS, change, List.copyOf(hashes), List.of(), List.of(), List.of());
    }

    private static Operation parsePacks(PackPresentationCollectionOperationProto source, boolean fullTransfer)
        throws IOException {
        var change = change(source.opType(), fullTransfer);
        requirePayload(change, source.entries().size(), source.hierarchyPaths().size());
        var entries = new ArrayList<PackValue>(source.entries().size());
        for (var value : source.entries()) {
            entries.add(readPack(value));
        }
        var paths = new ArrayList<String>(source.hierarchyPaths().size());
        for (var value : source.hierarchyPaths()) {
            paths.add(normalizedPackHierarchy(value));
        }
        var keys = change == Change.REMOVE ? List.copyOf(paths) : entries.stream().map(PackValue::hierarchy).toList();
        requireStrictOrder(keys, SessionCollectionPublication::compareUtf8, "pack hierarchy paths");
        return new Operation(CollectionKind.PACKS, change, keys, List.of(), List.copyOf(entries), List.of());
    }

    private static Operation parseAnimations(DefaultAnimationCollectionOperationProto source, boolean fullTransfer)
        throws IOException {
        var change = change(source.opType(), fullTransfer);
        requirePayload(change, source.entries().size(), source.names().size());
        var entries = new ArrayList<AnimationValue>(source.entries().size());
        for (var value : source.entries()) {
            if (value.name().isEmpty()) {
                throw new IOException("Default animation name is empty");
            }
            entries.add(new AnimationValue(value.name(), hash(value.hash(), "default animation hash")));
        }
        var names = new ArrayList<String>(source.names().size());
        for (var value : source.names()) {
            if (value.isEmpty()) {
                throw new IOException("Default animation name is empty");
            }
            names.add(value);
        }
        var keys = change == Change.REMOVE ? List.copyOf(names) : entries.stream().map(AnimationValue::name).toList();
        requireStrictOrder(keys, SessionCollectionPublication::compareUtf8, "default animation names");
        return new Operation(CollectionKind.ANIMATIONS, change, keys, List.of(), List.of(), List.copyOf(entries));
    }

    private static Change change(CollectionOperationType type, boolean fullTransfer) throws IOException {
        if (fullTransfer) {
            if (type != full()) {
                throw new IOException("Full publication contains a non-full operation");
            }
            return Change.FULL;
        }
        return switch (type) {
            case ADD -> Change.ADD;
            case REMOVE -> Change.REMOVE;
            case CLEAR -> Change.CLEAR;
            default -> throw new IOException("Delta publication contains an invalid operation");
        };
    }

    private static void requirePayload(Change change, int entryCount, int removeCount) throws IOException {
        switch (change) {
            case FULL -> {
                if (removeCount != 0) {
                    throw new IOException("Full operation contains remove keys");
                }
            }
            case ADD -> {
                if (entryCount == 0 || removeCount != 0) {
                    throw new IOException("Add operation has an invalid payload");
                }
            }
            case REMOVE -> {
                if (entryCount != 0 || removeCount == 0) {
                    throw new IOException("Remove operation has an invalid payload");
                }
            }
            case CLEAR -> {
                if (entryCount != 0 || removeCount != 0) {
                    throw new IOException("Clear operation has an invalid payload");
                }
            }
        }
    }

    private static void validateCanonical(List<Operation> operations, boolean fullTransfer) throws IOException {
        if (operations.isEmpty()) {
            throw new IOException("Session publication is empty");
        }
        var lastCollection = -1;
        var seenCollections = new boolean[CollectionKind.values().length];
        var emptyFull = new boolean[CollectionKind.values().length];
        var nonEmptyFull = new boolean[CollectionKind.values().length];
        var lastChange = new int[CollectionKind.values().length];
        Arrays.fill(lastChange, -1);
        var lastKey = new HashMap<Group, Object>();
        var addKeys = new HashMap<CollectionKind, Set<Object>>();
        var removeKeys = new HashMap<CollectionKind, Set<Object>>();
        var clearSeen = new boolean[CollectionKind.values().length];
        var addedCatalogPaths = new HashSet<HierarchyPath>();

        for (var operation : operations) {
            var collection = operation.collection();
            var collectionIndex = collection.ordinal();
            if (collectionIndex < lastCollection) {
                throw new IOException("Collection operations are not canonical");
            }
            lastCollection = collectionIndex;
            seenCollections[collectionIndex] = true;
            if (fullTransfer) {
                if (operation.change() != Change.FULL) {
                    throw new IOException("Full publication contains a delta operation");
                }
                if (operation.keys().isEmpty()) {
                    if (emptyFull[collectionIndex] || nonEmptyFull[collectionIndex]) {
                        throw new IOException("Empty full collection is not singular");
                    }
                    emptyFull[collectionIndex] = true;
                } else {
                    if (emptyFull[collectionIndex]) {
                        throw new IOException("Non-empty full collection has an empty packet");
                    }
                    nonEmptyFull[collectionIndex] = true;
                }
            } else {
                var changeOrder = operation.change().deltaOrder();
                if (changeOrder < lastChange[collectionIndex]) {
                    throw new IOException("Delta operations are not canonical");
                }
                lastChange[collectionIndex] = changeOrder;
                if (operation.change() == Change.CLEAR) {
                    if (clearSeen[collectionIndex]) {
                        throw new IOException("Delta collection has more than one clear");
                    }
                    clearSeen[collectionIndex] = true;
                } else if (operation.change() == Change.REMOVE && clearSeen[collectionIndex]) {
                    throw new IOException("Clear and remove cannot target the same collection");
                }
                var target =
                    operation.change() == Change.ADD ? addKeys.computeIfAbsent(collection, ignored -> new HashSet<>())
                        : operation.change() == Change.REMOVE
                            ? removeKeys.computeIfAbsent(collection, ignored -> new HashSet<>()) : null;
                if (target != null) {
                    for (var key : operation.keys()) {
                        if (!target.add(key)) {
                            throw new IOException("Delta repeats a natural key");
                        }
                    }
                }
            }
            if (!operation.keys().isEmpty()) {
                var group = new Group(collection, operation.change());
                var previous = lastKey.put(group, operation.keys().get(operation.keys().size() - 1));
                if (previous != null && compareKey(collection, previous, operation.keys().get(0)) >= 0) {
                    throw new IOException("Natural keys are not strictly ascending");
                }
            }
            if (operation.collection() == CollectionKind.CATALOG
                && (operation.change() == Change.FULL || operation.change() == Change.ADD)) {
                for (var entry : operation.catalogEntries()) {
                    if (!addedCatalogPaths.add(entry.path())) {
                        throw new IOException("Publication repeats a catalog hierarchy path");
                    }
                }
            }
        }
        if (fullTransfer) {
            for (var seen : seenCollections) {
                if (!seen) {
                    throw new IOException("Full publication does not cover every collection");
                }
            }
        } else {
            for (var collection : CollectionKind.values()) {
                var adds = addKeys.getOrDefault(collection, Set.of());
                var removes = removeKeys.getOrDefault(collection, Set.of());
                for (var key : adds) {
                    if (removes.contains(key)) {
                        throw new IOException("Delta adds and removes the same natural key");
                    }
                }
            }
        }
    }

    private static int apply(Candidate candidate, List<Operation> operations) {
        var missingRemovals = 0;
        for (var operation : operations) {
            switch (operation.collection()) {
                case CATALOG -> {
                    if (operation.change() == Change.CLEAR) {
                        candidate.entries.clear();
                    } else if (operation.change() == Change.REMOVE) {
                        for (var key : operation.keys()) {
                            if (candidate.entries.remove((Hash256)key) == null) {
                                missingRemovals++;
                            }
                        }
                    } else {
                        for (var entry : operation.catalogEntries()) {
                            candidate.entries.put(entry.modelId(), entry);
                        }
                    }
                }
                case GRANTS -> {
                    if (operation.change() == Change.CLEAR) {
                        candidate.grants.clear();
                    } else if (operation.change() == Change.REMOVE) {
                        for (var key : operation.keys()) {
                            if (!candidate.grants.remove(key)) {
                                missingRemovals++;
                            }
                        }
                    } else {
                        for (var key : operation.keys()) {
                            candidate.grants.add((Hash256)key);
                        }
                    }
                }
                case PACKS -> {
                    if (operation.change() == Change.CLEAR) {
                        candidate.packs.clear();
                    } else if (operation.change() == Change.REMOVE) {
                        for (var key : operation.keys()) {
                            if (candidate.packs.remove(key) == null) {
                                missingRemovals++;
                            }
                        }
                    } else {
                        for (var entry : operation.packEntries()) {
                            candidate.packs.put(entry.hierarchy(), entry);
                        }
                    }
                }
                case ANIMATIONS -> {
                    if (operation.change() == Change.CLEAR) {
                        candidate.animations.clear();
                    } else if (operation.change() == Change.REMOVE) {
                        for (var key : operation.keys()) {
                            if (candidate.animations.remove(key) == null) {
                                missingRemovals++;
                            }
                        }
                    } else {
                        for (var entry : operation.animationEntries()) {
                            candidate.animations.put(entry.name(), entry.hash());
                        }
                    }
                }
            }
        }
        return missingRemovals;
    }

    private static void requireAnimationContract(Map<String, Hash256> received, Map<String, Hash256> expected)
        throws IOException {
        if (!received.equals(expected)) {
            throw new IOException("Default animation contract mismatch");
        }
    }

    private static <T> void requireStrictOrder(List<T> values, Comparator<T> comparator, String description)
        throws IOException {
        for (var index = 1; index < values.size(); index++) {
            if (comparator.compare(values.get(index - 1), values.get(index)) >= 0) {
                throw new IOException("Non-canonical " + description);
            }
        }
    }

    private static int compareKey(CollectionKind collection, Object left, Object right) {
        return switch (collection) {
            case CATALOG, GRANTS -> ((Hash256)left).compareTo((Hash256)right);
            case PACKS, ANIMATIONS -> compareUtf8((String)left, (String)right);
        };
    }

    private static int compareUtf8(String left, String right) {
        return Arrays.compareUnsigned(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static void requireTransferId(long transferId) {
        if (transferId == 0) {
            throw new IllegalArgumentException("Publication ID must be unsigned-positive");
        }
    }

    private static CollectionOperationType full() {
        return CollectionOperationType.FULL;
    }

    private static CollectionOperationType add() {
        return CollectionOperationType.ADD;
    }

    private static CollectionOperationType remove() {
        return CollectionOperationType.REMOVE;
    }

    private static CollectionOperationType clear() {
        return CollectionOperationType.CLEAR;
    }

    private static Map<String, ModelPackDescriptor> packsByHierarchy(List<ModelPackDescriptor> packs) {
        var result = new LinkedHashMap<String, ModelPackDescriptor>();
        packs.stream().sorted((left, right) -> compareUtf8(left.hierarchy(), right.hierarchy()))
            .forEach(pack -> result.put(pack.hierarchy(), pack));
        return result;
    }

    private static boolean samePack(ModelPackDescriptor left, ModelPackDescriptor right) {
        return right != null && PackValue.from(left).equals(PackValue.from(right));
    }

    private static CatalogPublicationProto publication(CatalogRecord record,
        ServerModelSession.AuthoritySnapshot authority) {
        var content = record.binding().content();
        return CatalogPublicationProto.newBuilder().setModelId(ProtoBytes.wrap(record.entry().modelId()))
            .setContainerId(ProtoBytes.wrap(content.representation().containerId()))
            .setHierarchyPath(record.entry().path().value())
            .setAccess(
                record.entry().access() == CatalogAccess.PUBLIC ? com.elfmcys.ysm.proto.network.CatalogAccess.PUBLIC
                    : com.elfmcys.ysm.proto.network.CatalogAccess.AUTHORIZED)
            .setRuntimeContentAllowed(authority.runtimeContentAllowed(record.entry().modelId())).build();
    }

    private static PublicationEntry entry(CatalogRecord record, ServerModelSession.AuthoritySnapshot authority) {
        var content = record.binding().content();
        return new PublicationEntry(
            new ModelFileIdentity(record.entry().modelId(), content.representation().containerId()),
            record.entry().path(), record.entry().access(), authority.runtimeContentAllowed(record.entry().modelId()));
    }

    private static PublicationEntry readPublication(CatalogPublicationProto value) throws IOException {
        var modelId = hash(value.modelId(), "catalog model id");
        var containerId = hash(value.containerId(), "catalog container id");
        final HierarchyPath path;
        try {
            path = new HierarchyPath(value.hierarchyPath());
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid catalog hierarchy path", invalid);
        }
        var access = switch (value.access()) {
            case PUBLIC -> CatalogAccess.PUBLIC;
            case AUTHORIZED -> CatalogAccess.AUTHORIZED;
            default -> throw new IOException("Catalog access is unspecified");
        };
        return new PublicationEntry(new ModelFileIdentity(modelId, containerId), path, access,
            value.runtimeContentAllowed());
    }

    private static PackPresentationProto pack(ModelPackDescriptor pack) {
        return PackPresentationProto.newBuilder().setHierarchyPath(pack.hierarchy()).setDisplayName(pack.name())
            .setDescription(pack.description())
            .setCoverHash(pack.coverHash() == null ? ByteBuffer.allocate(0) : ByteBuffer.wrap(pack.coverHash().bytes()))
            .setCoverFormat(pack.coverFormat()).setCoverSize(pack.coverSize()).build();
    }

    private static PackValue readPack(PackPresentationProto value) throws IOException {
        if (value.coverSize() < 0
            || value.coverHash().remaining() != 0 && value.coverHash().remaining() != Hash256.SIZE) {
            throw new IOException("Invalid pack cover descriptor");
        }
        var coverHash = value.coverHash().remaining() == 0 ? null : hash(value.coverHash(), "pack cover hash");
        return new PackValue(normalizedPackHierarchy(value.hierarchyPath()), value.displayName(), value.description(),
            coverHash, value.coverFormat(), value.coverSize());
    }

    private static String normalizedPackHierarchy(String value) throws IOException {
        try {
            var normalized =
                new ModelPackDescriptor(CatalogRootKind.CUSTOM, value, "", "", Map.of(), null, "", 0).hierarchy();
            if (!normalized.equals(value)) {
                throw new IOException("Pack hierarchy path is not canonical");
            }
            return normalized;
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid pack hierarchy path", invalid);
        }
    }

    private static Hash256 hash(ByteBuffer bytes, String field) throws IOException {
        if (bytes.remaining() != Hash256.SIZE) {
            throw new IOException("Invalid " + field);
        }
        return new Hash256(ProtoBytes.copy(bytes));
    }

    record Limits(int fragments, long decodedBytes, int catalogRecords, int grantRecords, int packRecords,
        int animationRecords) {
        Limits {
            if (fragments <= 0 || decodedBytes <= 0 || catalogRecords <= 0 || grantRecords <= 0 || packRecords <= 0
                || animationRecords <= 0) {
                throw new IllegalArgumentException("Collection limits must be positive");
            }
        }
    }

    enum Status {
        PENDING, FULL, DELTA, BASELINE_DRIFT, INTRINSIC_INVALID
    }

    record Result(Status status, RemotePublicationSnapshot publication, Throwable cause, int missingRemovals) {
        private static Result pending() {
            return new Result(Status.PENDING, null, null, 0);
        }

        private static Result full(RemotePublicationSnapshot publication) {
            return new Result(Status.FULL, publication, null, 0);
        }

        private static Result delta(RemotePublicationSnapshot publication, int missingRemovals) {
            return new Result(Status.DELTA, publication, null, missingRemovals);
        }

        private static Result baselineDrift(Throwable cause) {
            return new Result(Status.BASELINE_DRIFT, null, cause, 0);
        }

        private static Result intrinsicInvalid(Throwable cause) {
            return new Result(Status.INTRINSIC_INVALID, null, cause, 0);
        }
    }

    private static final class Transfer {
        private final long transferId;
        private final boolean fullTransfer;
        private final Limits limits;
        private final NavigableMap<Integer, Fragment> fragments = new TreeMap<>();
        private final int[] recordCounts = new int[CollectionKind.values().length];
        private Integer finalSequence;
        private long decodedBytes;

        private Transfer(long transferId, boolean fullTransfer, Limits limits) {
            this.transferId = transferId;
            this.fullTransfer = fullTransfer;
            this.limits = limits;
        }

        private boolean accept(int sequence, boolean finalFragment, int fragmentBytes, Operation operation)
            throws IOException {
            var incoming = new Fragment(finalFragment, operation);
            var previous = fragments.get(sequence);
            if (previous != null) {
                if (!previous.equals(incoming)) {
                    throw new IOException("Conflicting publication sequence duplicate");
                }
                return complete();
            }
            if (finalSequence != null && sequence > finalSequence) {
                throw new IOException("Publication sequence is above its final sequence");
            }
            if (finalFragment) {
                if (finalSequence != null && finalSequence != sequence
                    || !fragments.isEmpty() && fragments.lastKey() > sequence) {
                    throw new IOException("Publication has conflicting final coverage");
                }
                finalSequence = sequence;
            }
            var nextBytes = Math.addExact(decodedBytes, fragmentBytes);
            if (nextBytes > limits.decodedBytes()) {
                throw new IOException("Publication exceeds decoded byte limit");
            }
            var collection = operation.collection().ordinal();
            var records = Math.max(1, operation.keys().size());
            var nextRecords = Math.addExact(recordCounts[collection], records);
            if (nextRecords > limitFor(operation.collection(), limits)) {
                throw new IOException("Publication collection exceeds record limit");
            }
            if (fragments.size() >= limits.fragments()) {
                throw new IOException("Publication exceeds fragment limit");
            }
            decodedBytes = nextBytes;
            recordCounts[collection] = nextRecords;
            fragments.put(sequence, incoming);
            return complete();
        }

        private boolean complete() {
            return finalSequence != null && fragments.size() == (long)finalSequence + 1 && fragments.firstKey() == 0
                && fragments.lastKey().equals(finalSequence);
        }

        private List<Operation> orderedOperations() {
            return fragments.values().stream().map(Fragment::operation).toList();
        }
    }

    private static int limitFor(CollectionKind collection, Limits limits) {
        return switch (collection) {
            case CATALOG -> limits.catalogRecords();
            case GRANTS -> limits.grantRecords();
            case PACKS -> limits.packRecords();
            case ANIMATIONS -> limits.animationRecords();
        };
    }

    private static final class Candidate {
        private final LinkedHashMap<Hash256, PublicationEntry> entries;
        private final LinkedHashSet<Hash256> grants;
        private final LinkedHashMap<String, PackValue> packs;
        private final LinkedHashMap<String, Hash256> animations;

        private Candidate(LinkedHashMap<Hash256, PublicationEntry> entries, LinkedHashSet<Hash256> grants,
            LinkedHashMap<String, PackValue> packs, LinkedHashMap<String, Hash256> animations) {
            this.entries = entries;
            this.grants = grants;
            this.packs = packs;
            this.animations = animations;
        }

        private static Candidate empty() {
            return new Candidate(new LinkedHashMap<>(), new LinkedHashSet<>(), new LinkedHashMap<>(),
                new LinkedHashMap<>());
        }

        private static Candidate from(RemotePublicationSnapshot previous) {
            Objects.requireNonNull(previous, "previous");
            var packs = new LinkedHashMap<String, PackValue>();
            previous.packs().forEach(pack -> packs.put(pack.hierarchy(), PackValue.from(pack)));
            return new Candidate(new LinkedHashMap<>(previous.entries()), new LinkedHashSet<>(previous.grants()), packs,
                new LinkedHashMap<>(previous.defaultAnimations()));
        }

        private RemotePublicationSnapshot snapshot() {
            var orderedPacks =
                packs.values().stream().sorted((left, right) -> compareUtf8(left.hierarchy(), right.hierarchy()))
                    .map(PackValue::domain).toList();
            return new RemotePublicationSnapshot(entries, grants, orderedPacks, animations);
        }
    }

    private enum CollectionKind {
        CATALOG, GRANTS, PACKS, ANIMATIONS
    }

    private enum Change {
        FULL(-1), CLEAR(0), REMOVE(1), ADD(2);

        private final int deltaOrder;

        Change(int deltaOrder) {
            this.deltaOrder = deltaOrder;
        }

        private int deltaOrder() {
            return deltaOrder;
        }
    }

    private record Operation(CollectionKind collection, Change change, List<?> keys,
        List<PublicationEntry> catalogEntries, List<PackValue> packEntries, List<AnimationValue> animationEntries) {
    }

    private record Fragment(boolean finalFragment, Operation operation) {
    }

    private record Group(CollectionKind collection, Change change) {
    }

    private record AnimationValue(String name, Hash256 hash) {
    }

    private record PackValue(String hierarchy, String name, String description, Hash256 coverHash, String coverFormat,
        int coverSize) {
        private static PackValue from(ModelPackDescriptor source) {
            return new PackValue(source.hierarchy(), source.name(), source.description(), source.coverHash(),
                source.coverFormat(), source.coverSize());
        }

        private ModelPackDescriptor domain() {
            return new ModelPackDescriptor(CatalogRootKind.CUSTOM, hierarchy, name, description, Map.of(), coverHash,
                coverFormat, coverSize);
        }
    }

    private record FullPart(CatalogCollectionOperationProto catalog, GrantCollectionOperationProto grants,
        PackPresentationCollectionOperationProto packs, DefaultAnimationCollectionOperationProto animations) {
        private static FullPart catalog(CatalogCollectionOperationProto value) {
            return new FullPart(value, null, null, null);
        }

        private static FullPart grants(GrantCollectionOperationProto value) {
            return new FullPart(null, value, null, null);
        }

        private static FullPart packs(PackPresentationCollectionOperationProto value) {
            return new FullPart(null, null, value, null);
        }

        private static FullPart animations(DefaultAnimationCollectionOperationProto value) {
            return new FullPart(null, null, null, value);
        }

        private SessionFullFragmentProto message(long transferId, int sequence, boolean terminal) {
            var result = SessionFullFragmentProto.newBuilder().setTransferId(transferId).setSequence(sequence)
                .setFinalFragment(terminal);
            if (catalog != null)
                return result.setCatalog(catalog).build();
            if (grants != null)
                return result.setGrants(grants).build();
            if (packs != null)
                return result.setPackPresentations(packs).build();
            return result.setDefaultAnimations(animations).build();
        }
    }

    private record DeltaPart(CatalogCollectionOperationProto catalog, GrantCollectionOperationProto grants,
        PackPresentationCollectionOperationProto packs) {
        private static DeltaPart catalog(CatalogCollectionOperationProto value) {
            return new DeltaPart(value, null, null);
        }

        private static DeltaPart grants(GrantCollectionOperationProto value) {
            return new DeltaPart(null, value, null);
        }

        private static DeltaPart packs(PackPresentationCollectionOperationProto value) {
            return new DeltaPart(null, null, value);
        }

        private CollectionKind kind() {
            if (catalog != null)
                return CollectionKind.CATALOG;
            if (grants != null)
                return CollectionKind.GRANTS;
            return CollectionKind.PACKS;
        }

        private SessionDeltaFragmentProto message(long transferId, int sequence, boolean terminal) {
            var result = SessionDeltaFragmentProto.newBuilder().setTransferId(transferId).setSequence(sequence)
                .setFinalFragment(terminal);
            if (catalog != null)
                return result.setCatalog(catalog).build();
            if (grants != null)
                return result.setGrants(grants).build();
            return result.setPackPresentations(packs).build();
        }
    }
}
