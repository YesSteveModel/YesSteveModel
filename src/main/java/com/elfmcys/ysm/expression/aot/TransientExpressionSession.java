package com.elfmcys.ysm.expression.aot;

import com.elfmcys.ysm.expression.frontend.MolangFrontend;
import com.elfmcys.ysm.expression.host.ExpressionIdentityHash;
import com.elfmcys.ysm.expression.host.MolangContext;
import dev.semaweave.LocalName;
import dev.semaweave.CompilationPolicy;
import dev.semaweave.ProgramKey;
import dev.semaweave.ProgramShape;
import dev.semaweave.ValueType;
import dev.semaweave.SemanticFailurePolicy;
import dev.semaweave.backend.janino.JaninoJavaCompilerBackend;
import dev.semaweave.compilation.TaskContext;
import dev.semaweave.compilation.TransientAccessRequest;
import dev.semaweave.compilation.TransientCompilers;
import dev.semaweave.compilation.TransientDynamicAccess;
import dev.semaweave.compilation.TransientExecutionAccess;
import dev.semaweave.compilation.TransientLayout;
import dev.semaweave.compilation.TransientProgramLease;
import dev.semaweave.compilation.TransientPurpose;
import dev.semaweave.compilation.ResourceGenerationToken;

import com.elfmcys.ysm.util.CleanerUtil;
import com.elfmcys.ysm.util.ResourceDiagnostics;
import dev.semaweave.compilation.EngineMetrics;
import java.lang.ref.Cleaner;
import java.lang.ref.Reference;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Owns one transient program lease and, after attachment, its exact installation's execution access.
 *
 * <p>Execution: compilation can use a background thread after the owner freezes CompilationInputs.
 * Attachment, execution, and explicit close are serialized by the owner. Atomic close tracking does
 * not permit close to race with execution or attachment.
 *
 * <p>Ownership: the receiver closes an accepted session. A rejected or late result must be closed
 * by the handoff owner. Cancellation requests do not transfer this responsibility.
 *
 * <p>Seam: implements [transient execution](docs/architecture/animation/molang-runtime.md#短期执行).
 */
public final class TransientExpressionSession implements AutoCloseable {
    /** Maximum UTF-8 source length accepted by one compilation. */
    public static final int MAX_SOURCE_BYTES = 4096;
    /** Global count bound for in-progress compilations and live program sessions together. */
    public static final int MAX_LIVE_LEASES = 64;

    private static final AtomicInteger LIVE_LEASES = new AtomicInteger();
    private static final ExpressionEngineMetrics RESOURCE_METRICS = new ExpressionEngineMetrics();
    private final ResourceGenerationToken targetGeneration;
    private final long entityGeneration;
    private final Privilege privilege;
    private final String sourceLocation;
    private final TransientLayout<MolangContext> layout;
    private final TransientDynamicAccess dynamicAccess;
    private final TransientProgramLease<MolangContext> lease;
    private TransientExecutionAccess<MolangContext> access;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Cleaner.Cleanable cleanable;

    private TransientExpressionSession(ResourceGenerationToken targetGeneration, long entityGeneration,
        Privilege privilege, String sourceLocation, TransientLayout<MolangContext> layout,
        TransientDynamicAccess dynamicAccess, TransientProgramLease<MolangContext> lease) {
        this.targetGeneration = targetGeneration;
        this.entityGeneration = entityGeneration;
        this.privilege = privilege;
        this.sourceLocation = sourceLocation;
        this.layout = layout;
        this.dynamicAccess = dynamicAccess;
        this.lease = lease;
        cleanable = CleanerUtil.ref(this, lease, closed, (owned, released) -> {
            if (released.compareAndSet(false, true)) {
                try {
                    owned.close();
                } finally {
                    LIVE_LEASES.decrementAndGet();
                }
            }
        });
    }

    record CompilationInputs(ResourceGenerationToken generation, TransientLayout<MolangContext> layout,
        TransientDynamicAccess dynamic) {}

    /**
     * Freezes the module's transient layout and privilege on the owner before background compilation.
     *
     * <p>Ownership: the caller must retain the source module's resource lease through compilation.
     * The returned inputs grant no access to the owner's mutable expression instance.
     */
    static CompilationInputs prepare(RenderTargetExpressionModule module, Privilege privilege) {
        var instanceNames = new TreeSet<LocalName>(Comparator.comparing(LocalName::name));
        var writes = new TreeSet<LocalName>(Comparator.comparing(LocalName::name));
        var userFunctions = new TreeSet<ProgramKey>();
        instanceNames.addAll(module.transientInstanceSlots());
        writes.addAll(instanceNames);
        userFunctions.addAll(module.transientUserFunctions());
        var dynamic = TransientDynamicAccess.none(ExpressionIdentityHash.SHA_256);
        var persistentPolicy = module.host().compilationPolicy();
        var transientPolicy = new CompilationPolicy(persistentPolicy.missingSymbols(),
            SemanticFailurePolicy.FAIL_UNIT, persistentPolicy.arityMismatch(), persistentPolicy.numericPolicy(),
            persistentPolicy.executionLimits(), persistentPolicy.compilerLimits(),
            persistentPolicy.objectLanguageRules());
        var request = new TransientAccessRequest(instanceNames, writes, userFunctions, dynamic.descriptor(),
            transientPolicy, new dev.semaweave.EntryPermissions(privilege.effects()));
        TransientLayout<MolangContext> layout = module.createTransientLayout(request);
        return new CompilationInputs(module.generation(), layout, dynamic);
    }

    static TransientExpressionSession compile(RenderTargetExpressionModule module, long entityGeneration, String source,
        Privilege privilege, Purpose purpose, String sourceLocation, BooleanSupplier cancelled) throws Exception {
        return compile(prepare(module, privilege), entityGeneration, source, privilege, purpose, sourceLocation, cancelled);
    }

    /**
     * Compiles independent transient code from frozen inputs without attaching owner execution access.
     *
     * <p>Failure: source, compilation, cancellation, or live-lease admission failure releases this attempt's
     * program lease and count reservation. Success does not prove that the source installation is still current.
     *
     * @return an owned, unattached session that the recipient must attach or close
     */
    static TransientExpressionSession compile(CompilationInputs prepared, long entityGeneration, String source,
        Privilege privilege, Purpose purpose, String sourceLocation, BooleanSupplier cancelled) throws Exception {
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(privilege, "privilege");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(sourceLocation, "sourceLocation");
        Objects.requireNonNull(cancelled, "cancelled");
        if (entityGeneration <= 0) {
            throw new IllegalArgumentException("entity generation must be current");
        }
        if (source.length() > MAX_SOURCE_BYTES || source.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES) {
            throw new IllegalArgumentException("transient source exceeds " + MAX_SOURCE_BYTES + " UTF-8 bytes");
        }
        if (LIVE_LEASES.incrementAndGet() > MAX_LIVE_LEASES) {
            LIVE_LEASES.decrementAndGet();
            throw new IllegalStateException("transient live lease bound exceeded");
        }

        TransientProgramLease<MolangContext> lease = null;
        try {
            checkCancelled(cancelled);
            var frontend = new MolangFrontend();
            var lowered = frontend.compile(source, MolangFrontend.SourceMode.EXPRESSION,
                new MolangFrontend.SourceLocation(sourceLocation));
            checkCancelled(cancelled);

            var layout = prepared.layout();
            var dynamic = prepared.dynamic();
            checkCancelled(cancelled);
            var metrics = ResourceDiagnostics.isEnabled() ? RESOURCE_METRICS : EngineMetrics.none();
            lease = TransientCompilers.<MolangContext>standard(new JaninoJavaCompilerBackend(), metrics).compile(
                (TaskContext)cancelled::getAsBoolean, lowered.program(), ProgramShape.VALUE, layout,
                purpose == Purpose.WATCH ? TransientPurpose.WATCH : TransientPurpose.ONE_SHOT,
                ExpressionIdentityHash.SHA_256);
            checkCancelled(cancelled);
            return new TransientExpressionSession(prepared.generation(), entityGeneration, privilege, sourceLocation,
                layout, dynamic, lease);
        } catch (Throwable failure) {
            try {
                if (lease != null)
                    lease.close();
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
            } finally {
                LIVE_LEASES.decrementAndGet();
            }
            if (failure instanceof Exception exception) {
                throw exception;
            }
            throw failure;
        }
    }

    /**
     * Takes independently owned execution access after the owner qualifies the exact installation and generation.
     *
     * <p>Ownership: every entered call consumes value, including closed-session and duplicate-attachment failure.
     */
    void attach(TransientExecutionAccess<MolangContext> value) {
        Objects.requireNonNull(value, "access");
        try {
            requireOpen();
            if (access != null)
                throw new IllegalStateException("transient execution access is already prepared");
            access = value;
        } catch (Throwable failure) {
            try {
                value.close();
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /**
     * Executes within the current qualified owner round using this session's attached access.
     *
     * <p>Requires: the owner has qualified the session's installation and generation. Context, subject binding,
     * and frame remain exclusively borrowed until execution and its cleanup return.
     * The caller must not close this session concurrently.
     */
    Object execute(MolangContext context, dev.semaweave.SubjectBinding subjectBinding,
        dev.semaweave.ExecutionFrame frame) throws Throwable {
        try {
            requireOpen();
            if (access == null)
                throw new IllegalStateException("transient execution access is not prepared");
            return lease.program().executeValue(access, context, subjectBinding, frame);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    public ValueType resultType() {
        try {
            requireOpen();
            return lease.resultProfile().type();
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    TransientLayout<MolangContext> layout() {
        requireOpen();
        return layout;
    }

    TransientDynamicAccess dynamicAccess() {
        requireOpen();
        return dynamicAccess;
    }

    ResourceGenerationToken targetGeneration() {
        return targetGeneration;
    }

    long entityGeneration() {
        return entityGeneration;
    }

    public Privilege privilege() {
        return privilege;
    }

    String sourceLocation() {
        return sourceLocation;
    }

    /**
     * Observes live sessions plus reservations held by in-progress compilations.
     *
     * @return the current count; the observation does not reserve admission for another compilation
     */
    public static int liveLeaseCount() {
        return LIVE_LEASES.get();
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("transient expression session is closed");
        }
    }

    /**
     * Releases attached execution access and the independent program lease without waiting for execution.
     *
     * <p>Requires: no attachment or execution is in progress. Repeated serial close calls release no second lease.
     * A cleanup failure still attempts program-lease cleanup and propagates the primary cleanup failure.
     */
    @Override
    public void close() {
        try {
            var release = access;
            access = null;
            try {
                if (release != null)
                    release.close();
            } catch (RuntimeException | Error failure) {
                try {
                    cleanable.clean();
                } catch (Throwable cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
            cleanable.clean();
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            throw new java.util.concurrent.CancellationException("transient expression compilation cancelled");
        }
    }

    public enum Privilege {
        LOCAL_DEBUG(false), LOCAL_WATCH(false), LOCAL_EXECUTE(true), SERVER_ADMIN_ACTION(true),
        LOCAL_OPTIONAL_MOD(false);

        private final boolean effects;

        Privilege(boolean effects) {
            this.effects = effects;
        }

        public boolean effects() {
            return effects;
        }

    }

    public enum Purpose {
        ONE_SHOT, WATCH
    }
}
