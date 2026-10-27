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

/** Exact-generation owner for one privileged, source-origin transient program. */
public final class TransientExpressionSession implements AutoCloseable {
    public static final int MAX_SOURCE_BYTES = 4096;
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

    static TransientExpressionSession compile(RenderTargetExpressionModule module, long entityGeneration, String source,
        Privilege privilege, Purpose purpose, String sourceLocation, BooleanSupplier cancelled) throws Exception {
        Objects.requireNonNull(module, "module");
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
            checkCancelled(cancelled);
            var metrics = ResourceDiagnostics.isEnabled() ? RESOURCE_METRICS : EngineMetrics.none();
            lease = TransientCompilers.<MolangContext>standard(new JaninoJavaCompilerBackend(), metrics).compile(
                (TaskContext)cancelled::getAsBoolean, lowered.program(), ProgramShape.VALUE, layout,
                purpose == Purpose.WATCH ? TransientPurpose.WATCH : TransientPurpose.ONE_SHOT,
                ExpressionIdentityHash.SHA_256);
            checkCancelled(cancelled);
            return new TransientExpressionSession(module.generation(), entityGeneration, privilege, sourceLocation,
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

    public static int liveLeaseCount() {
        return LIVE_LEASES.get();
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("transient expression session is closed");
        }
    }

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
