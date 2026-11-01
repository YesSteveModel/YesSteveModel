package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.api.molang.PreparedEffect;
import com.elfmcys.ysm.client.compat.bettercombat.BetterCombatBuiltinComponent;
import com.elfmcys.ysm.client.compat.carryon.CarryOnBuiltinComponent;
import com.elfmcys.ysm.client.compat.immersivemelodies.ImmersiveMelodiesBuiltinComponent;
import com.elfmcys.ysm.expression.host.builtin.ModelBuiltins;
import com.elfmcys.ysm.expression.host.builtin.ControlBuiltins;
import com.elfmcys.ysm.expression.host.builtin.CuriosBuiltins;
import com.elfmcys.ysm.expression.host.builtin.PositionComponent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import sun.misc.Unsafe;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import com.elfmcys.ysm.api.internal.processor.UnifiedDeclarationProcessor;
import com.elfmcys.ysm.api.molang.DeclarationSchema;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.geckolib3.geo.RenderContext;
import com.elfmcys.ysm.geckolib3.model.provider.data.EntityModelData;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

final class ComponentScopeTest {
    private static Unsafe unsafe;
    private static Field positionField;

    @BeforeAll
    static void initializeMinecraftEntityClasses() throws ReflectiveOperationException {
        SharedConstants.tryDetectVersion();
        var gate = Bootstrap.class.getDeclaredField("isBootstrapped");
        gate.setAccessible(true);
        boolean previous = gate.getBoolean(null);
        gate.setBoolean(null, true);
        try {
            BuiltInRegistries.REGISTRY.size();
            Class.forName(TestEntity.class.getName(), true, ComponentScopeTest.class.getClassLoader());
            Class.forName(ArmorStand.class.getName(), true, ComponentScopeTest.class.getClassLoader());
        } finally {
            gate.setBoolean(null, previous);
        }
        var unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        unsafe = (Unsafe)unsafeField.get(null);
        positionField = Entity.class.getDeclaredField("position");
        positionField.setAccessible(true);
    }

    @Test
    void oneOwnerKeepsExactComponentIdentityAndTwoOwnersDoNotShareIt() {
        var entity = allocate(TestEntity.class);
        var first = BuiltinDeclarations.createComponents(TestComponentHolders.owner(entity),
            ExpressionHost.create(HostAvailability.none()));
        var second = BuiltinDeclarations.createComponents(TestComponentHolders.owner(entity),
            ExpressionHost.create(HostAvailability.none()));

        var firstComponent = first.exact(PositionComponent.class).orElseThrow();
        assertSame(firstComponent, first.exact(PositionComponent.class).orElseThrow());
        assertNotSame(firstComponent, second.exact(PositionComponent.class).orElseThrow());

        first.invalidate();
        assertTrue(first.exact(PositionComponent.class).isEmpty());
        assertTrue(second.exact(PositionComponent.class).isPresent());
    }

    @Test
    void builtinNamespacesShareOnlyTheirComponentPrivateSequence(@TempDir Path temporary) throws Exception {
        var declarations = compileBuiltinCounters(temporary);
        var a = declarations.get(0);
        var b = declarations.get(1);
        assertTrue(a.schema().builtin());
        assertTrue(b.schema().builtin());
        assertEquals("query", a.schema().namespace());
        assertEquals("query", b.schema().namespace());
        assertEquals(List.of("query:counter_a", "ysm:counter_a"),
            a.schema().members().stream().map(DeclarationSchema.Member::symbol).sorted().toList());
        assertEquals(List.of("query:counter_b"),
            b.schema().members().stream().map(DeclarationSchema.Member::symbol).toList());
        var host = ExpressionHost.createFrozen(HostAvailability.none(), ExpressionPolicy.COMPILATION,
            declarations, declarations);
        for (String symbol : List.of("query:counter_a", "ysm:counter_a", "query:counter_b"))
            assertTrue(host.registry().function(new dev.semaweave.SymbolKey(symbol)).isPresent(), symbol);

        var firstEntity = allocate(TestEntity.class);
        var secondEntity = allocate(TestEntity.class);
        var first = new DeclarationComponentScope(TestComponentHolders.owner(firstEntity), host.declarations());
        var second = new DeclarationComponentScope(TestComponentHolders.owner(secondEntity), host.declarations());
        var firstContext = readContext((Entity)firstEntity);
        var secondContext = readContext((Entity)secondEntity);
        try {
            for (var declaration : declarations) {
                var firstProvider = first.exact(declaration.owner()).orElseThrow();
                assertSame(firstProvider,
                    declaration.helper().getMethod("provider").invoke(first.exact(declaration.helper()).orElseThrow()));
                assertNotSame(firstProvider, second.exact(declaration.owner()).orElseThrow());
            }
            assertArrayEquals(new int[] {1, 1, 2, 1, 3, 1, 2, 2, 4, 3, 2},
                new int[] {nextBuiltinCounter(first, a, "query:counter_a", firstContext),
                    nextBuiltinCounter(first, b, "query:counter_b", firstContext),
                    nextBuiltinCounter(first, a, "ysm:counter_a", firstContext),
                    nextBuiltinCounter(second, a, "ysm:counter_a", secondContext),
                    nextBuiltinCounter(first, a, "query:counter_a", firstContext),
                    nextBuiltinCounter(second, b, "query:counter_b", secondContext),
                    nextBuiltinCounter(first, b, "query:counter_b", firstContext),
                    nextBuiltinCounter(second, a, "query:counter_a", secondContext),
                    nextBuiltinCounter(first, a, "ysm:counter_a", firstContext),
                    nextBuiltinCounter(second, a, "ysm:counter_a", secondContext),
                    nextBuiltinCounter(second, b, "query:counter_b", secondContext)});
        } finally {
            firstContext.closeRound();
            secondContext.closeRound();
            first.invalidate();
            second.invalidate();
        }
    }

    private static List<LinkedDeclaration> compileBuiltinCounters(Path temporary) throws Exception {
        var classes = Files.createDirectory(temporary.resolve("classes"));
        var generated = Files.createDirectory(temporary.resolve("generated"));
        var names = List.of("ComponentScopeBuiltinCounterA", "ComponentScopeBuiltinCounterB");
        var units = new ArrayList<JavaFileObject>();
        for (int index = 0; index < names.size(); index++) {
            String name = names.get(index);
            String symbol = index == 0 ? "counter_a" : "counter_b";
            String secondNamespace = index == 0 ? """
                @BuiltinFunction(namespace="ysm", value="counter_a")
                public int ysm(MolangContextView<Entity> context) { return next(context); }
                """ : "";
            String source = """
                package com.elfmcys.ysm.expression.host;
                import com.elfmcys.ysm.api.molang.MolangContextView;
                import com.elfmcys.ysm.client.entity.AnimatableEntity;
                import com.elfmcys.ysm.expression.host.builtin.*;
                import net.minecraft.world.entity.Entity;
                @BuiltinComponent("query")
                public final class %s implements EntityComponent<Entity> {
                    private final Entity subject;
                    private int counter;
                    public %s(AnimatableEntity<Entity> owner) { subject = owner.getEntity(); }
                    @BuiltinFunction("%s")
                    public int query(MolangContextView<Entity> context) { return next(context); }
                    %s
                    private int next(MolangContextView<Entity> context) {
                        if (context.subject() != subject) throw new IllegalStateException("wrong counter owner");
                        return ++counter;
                    }
                }
                """.formatted(name, name, symbol, secondNamespace);
            units.add(new SimpleJavaFileObject(
                URI.create("string:///com/elfmcys/ysm/expression/host/" + name + ".java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return source;
                }
            });
        }
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            var task = compiler.getTask(
                null, manager, diagnostics, List.of("--release", "17", "-classpath",
                    System.getProperty("java.class.path"), "-d", classes.toString(), "-s", generated.toString()),
                null, units);
            task.setProcessors(List.of(new UnifiedDeclarationProcessor()));
            assertTrue(task.call(), diagnostics.getDiagnostics().toString());
        }
        var declarations = new ArrayList<LinkedDeclaration>();
        // Defining both generated classes in the host loader preserves its exact-visibility linkage boundary.
        var lookup = MethodHandles.privateLookupIn(MolangContext.class, MethodHandles.lookup());
        for (String name : names) {
            String owner = "com.elfmcys.ysm.expression.host." + name;
            DeclarationSchema schema;
            try (var input = Files.newInputStream(classes.resolve(DeclarationSchema.RESOURCE_ROOT + owner + ".bin"))) {
                schema = DeclarationSchema.decode(input);
            }
            lookup.defineClass(Files.readAllBytes(classes.resolve(schema.owner().replace('.', '/') + ".class")));
            lookup.defineClass(Files.readAllBytes(classes.resolve(schema.helper().replace('.', '/') + ".class")));
            declarations.add(LinkedDeclaration.link(schema, MolangContext.class.getClassLoader()));
        }
        return List.copyOf(declarations);
    }

    private static int nextBuiltinCounter(DeclarationComponentScope holder, LinkedDeclaration declaration,
        String symbol, MolangContext<?> context) throws ReflectiveOperationException {
        var member =
            declaration.schema().members().stream().filter(m -> m.symbol().equals(symbol)).findFirst().orElseThrow();
        context.bindExpression(symbol, 0);
        try {
            context.beginTopLevel(false);
            try {
                return (int)declaration.helper().getMethod(member.adapter(), MolangContext.class)
                    .invoke(holder.exact(declaration.helper()).orElseThrow(), context);
            } finally {
                context.endTopLevel();
            }
        } finally {
            context.releaseExpression();
        }
    }

    @Test
    void optionalGroupsRequireBothAvailabilityAndApplicableSubjectType() {
        var living = allocate(ArmorStand.class);
        var absent = BuiltinDeclarations.createComponents(TestComponentHolders.owner(living),
            ExpressionHost.create(HostAvailability.none()));
        var present = BuiltinDeclarations.createComponents(TestComponentHolders.owner(living),
            ExpressionHost.create(HostAvailability.all()));

        assertTrue(absent.exact(CarryOnBuiltinComponent.class).isEmpty());
        assertTrue(present.exact(CarryOnBuiltinComponent.class).isEmpty());
        assertTrue(present.exact(ImmersiveMelodiesBuiltinComponent.class).isPresent());
        assertTrue(present.exact(BetterCombatBuiltinComponent.class).isEmpty());
    }

    @Test
    void positionObservationsBorrowTheirOwnInputsWithoutMovingMainHistory() {
        var entity = allocate(TestEntity.class);
        var owner = TestComponentHolders.owner(entity);
        var host = ExpressionHost.create(HostAvailability.none());
        var holder = BuiltinDeclarations.createComponents(owner, host);
        var position = holder.exact(PositionComponent.class).orElseThrow();
        setPosition(entity, 2, 3, 4);
        holder.evaluate(event(owner, 1), () -> true, () -> holder.update(5, 5, 1, () -> true));
        assertEquals(0, position.delta().lengthSqr());
        assertEquals(0, position.trackedGroundSpeed());
        entity.xo = 2;
        entity.yo = 3;
        entity.zo = 4;
        setPosition(entity, 5, 7, 9);
        holder.evaluate(event(owner, 1), () -> true, () -> holder.update(5, 7, 1, () -> true));
        assertEquals(50, position.delta().lengthSqr());
        assertEquals(2, position.renderTickDelta());
        assertEquals(40, position.verticalSpeed());
        float[] horizontal = new float[2];
        holder.evaluate(event(owner, 0.25f), () -> true, () -> {
            assertEquals(Float.valueOf(2.75f), position.positionValue(0, false));
            assertEquals(0.25f, position.partialTick());
            horizontal[0] = position.inputHorizontal();
        });
        holder.evaluate(event(owner, 0.75f), () -> true, () -> {
            assertEquals(Float.valueOf(4.25f), position.positionValue(0, false));
            assertEquals(0.75f, position.partialTick());
            horizontal[1] = position.inputHorizontal();
        });
        assertTrue(Math.abs(horizontal[0] - horizontal[1]) > 0.1f);
        assertEquals(Float.valueOf(5), position.positionValue(0, false));
        assertEquals(1, position.partialTick());
        assertEquals(50, position.delta().lengthSqr());
        assertEquals(40, position.verticalSpeed());
        {
            var context = readContext((Entity)entity);
            context.bindExpression("component", 0);
            context.beginTopLevel(false);
            try {
                // Ingress reads use their call-local partial tick after the render borrow has ended.
                float local = position.inputHorizontal(context);
                assertTrue(Math.abs(local - position.inputHorizontal()) > 0.1f);
            } finally {
                context.endTopLevel();
                context.releaseExpression();
                context.closeRound();
            }
        }
        var replacement = BuiltinDeclarations.createComponents(owner, host);
        var next = replacement.exact(PositionComponent.class).orElseThrow();
        next.copyHistoryFrom(position);
        next.update(9, 9, 0.25f);
        assertEquals(Float.valueOf(5), next.positionValue(0, false));
        assertEquals(50, next.delta().lengthSqr());
        entity.xo = 5;
        entity.yo = 7;
        entity.zo = 9;
        setPosition(entity, 6, 9, 12);
        next.update(9, 10, 1);
        assertEquals(14, next.delta().lengthSqr());
        assertEquals(3, next.renderTickDelta());
        var differentOwner = BuiltinDeclarations.createComponents(TestComponentHolders.owner(entity), host);
        assertThrows(IllegalArgumentException.class,
            () -> differentOwner.exact(PositionComponent.class).orElseThrow().copyHistoryFrom(position));
    }

    @Test
    void beginFailureCleansOnlyTheSuccessfulPrefixAndKeepsHookIdentity() {
        var holder = hooks();
        var failure = new IllegalStateException("begin-source");
        holder.exact(HookB.class).orElseThrow().failBegin = failure;
        var thrown = assertThrows(ComponentLifecycleException.class,
            () -> holder.evaluate(event(holder.owner(), 0), () -> true, () -> calls.add("body")));
        assertEquals(ComponentLifecycleException.Phase.BEGIN_EVAL, thrown.phase());
        assertSame(HookB.class, thrown.declaration());
        assertSame(failure, thrown.getCause());
        assertEquals(List.of("A.begin", "B.begin", "A.end"), calls);
    }

    @Test
    void endFailureKeepsTheBodyFailureAndStillCleansEveryBorrow() {
        var holder = hooks();
        var bodyFailure = new IllegalArgumentException("body-source");
        var endFailure = new IllegalStateException("end-source");
        holder.exact(HookB.class).orElseThrow().failEnd = endFailure;
        var thrown = assertThrows(IllegalArgumentException.class,
            () -> holder.evaluate(event(holder.owner(), 0), () -> true, () -> {
                throw bodyFailure;
            }));
        assertSame(bodyFailure, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        var cleanup = (ComponentLifecycleException)thrown.getSuppressed()[0];
        assertSame(HookB.class, cleanup.declaration());
        assertEquals(ComponentLifecycleException.Phase.END_EVAL, cleanup.phase());
        assertSame(endFailure, cleanup.getCause());
        assertEquals(List.of("A.begin", "B.begin", "C.begin", "C.end", "B.end", "A.end"), calls);
    }

    @Test
    void invalidationAndQualificationLossStopWorkButRetainCleanupObjects() {
        var holder = hooks();
        holder.exact(HookB.class).orElseThrow().afterBegin = holder::invalidate;
        assertThrows(CancellationException.class,
            () -> holder.evaluate(event(holder.owner(), 0), () -> true, () -> calls.add("body")));
        assertEquals(List.of("A.begin", "B.begin", "B.end", "A.end"), calls);
        assertTrue(holder.exact(HookA.class).isEmpty());
        holder.invalidate();
        var next = hooks();
        var current = new AtomicBoolean(true);
        next.exact(HookA.class).orElseThrow().afterTick = () -> current.set(false);
        assertThrows(CancellationException.class, () -> next.evaluate(event(next.owner(), 0), current::get, () -> {
            next.tick(2, 8, current::get);
            next.update(3, 7, 0, current::get);
        }));
        assertEquals(List.of("A.begin", "B.begin", "C.begin", "A.tick:2:8", "C.end", "B.end", "A.end"), calls);
    }

    @Test
    void fiveHooksUseExplicitSpansAndActualTargetIdentity() {
        var holder = hooks();
        var a = allocate(ModelRenderTarget.class);
        var b = allocate(ModelRenderTarget.class);
        holder.setRenderTarget(a, () -> true);
        holder.setRenderTarget(a, () -> true);
        holder.evaluate(event(holder.owner(), 0.75f), () -> true, () -> {
            holder.tick(2, 8, () -> true);
            holder.update(1, 6, 0.75f, () -> true);
            calls.add("body");
        });
        holder.setRenderTarget(b, () -> true);
        assertEquals(List.of("A.target", "B.target", "C.target", "A.begin", "B.begin", "C.begin", "A.tick:2:8",
            "B.tick:2:8", "C.tick:2:8", "A.update:1.0:6.0:0.75", "B.update:1.0:6.0:0.75", "C.update:1.0:6.0:0.75",
            "body", "C.end", "B.end", "A.end", "A.target", "B.target", "C.target"), calls);
        assertSame(b, holder.exact(HookA.class).orElseThrow().target);
    }

    @Test
    void cancellationThrownByAHookKeepsComponentFailureProvenance() {
        var holder = hooks();
        var cause = new CancellationException("hook-owned-cancellation");
        holder.exact(HookB.class).orElseThrow().failUpdate = cause;
        var failure = assertThrows(ComponentLifecycleException.class,
            () -> holder.evaluate(event(holder.owner(), 0), () -> true, () -> holder.update(1, 2, 0, () -> true)));
        assertSame(cause, failure.getCause());
        assertEquals(ComponentLifecycleException.Phase.UPDATE, failure.phase());
        assertSame(HookB.class, failure.declaration());
        assertEquals(List.of("A.begin", "B.begin", "C.begin", "A.update:1.0:2.0:0.0", "B.update:1.0:2.0:0.0", "C.end",
            "B.end", "A.end"), calls);
    }

    @Test
    void targetTickAndUpdateFailuresStopDispatchAndPreserveTheirRealPhase() {
        var targetHolder = hooks();
        var targetCause = new NoClassDefFoundError("target-linkage-source");
        targetHolder.exact(HookB.class).orElseThrow().failTarget = targetCause;
        var targetFailure = assertThrows(ComponentLifecycleException.class,
            () -> targetHolder.setRenderTarget(allocate(ModelRenderTarget.class), () -> true));
        assertEquals(ComponentLifecycleException.Phase.SET_RENDER_TARGET, targetFailure.phase());
        assertSame(targetCause, targetFailure.getCause());
        assertEquals(List.of("A.target", "B.target"), calls);
        var tickHolder = hooks();
        var tickCause = new IllegalStateException("tick-source");
        tickHolder.exact(HookB.class).orElseThrow().failTick = tickCause;
        var tickFailure = assertThrows(ComponentLifecycleException.class, () -> tickHolder
            .evaluate(event(tickHolder.owner(), 0), () -> true, () -> tickHolder.tick(1, 8, () -> true)));
        assertEquals(ComponentLifecycleException.Phase.TICK, tickFailure.phase());
        assertSame(HookB.class, tickFailure.declaration());
        assertSame(tickCause, tickFailure.getCause());
        assertEquals(List.of("A.begin", "B.begin", "C.begin", "A.tick:1:8", "B.tick:1:8", "C.end", "B.end", "A.end"),
            calls);
        var updateHolder = hooks();
        var updateCause = new IllegalStateException("update-source");
        updateHolder.exact(HookB.class).orElseThrow().failUpdate = updateCause;
        var updateFailure = assertThrows(ComponentLifecycleException.class, () -> updateHolder
            .evaluate(event(updateHolder.owner(), 0), () -> true, () -> updateHolder.update(1, 8, 0, () -> true)));
        assertEquals(ComponentLifecycleException.Phase.UPDATE, updateFailure.phase());
        assertSame(HookB.class, updateFailure.declaration());
        assertSame(updateCause, updateFailure.getCause());
        assertEquals(List.of("A.begin", "B.begin", "C.begin", "A.update:1.0:8.0:0.0", "B.update:1.0:8.0:0.0", "C.end",
            "B.end", "A.end"), calls);
    }

    @Test
    void firstEndFailureOwnsTheFailureAndLaterCleanupFailuresAreSuppressed() {
        var holder = hooks();
        var first = new IllegalStateException("C-end-source");
        var second = new IllegalStateException("B-end-source");
        holder.exact(HookC.class).orElseThrow().failEnd = first;
        holder.exact(HookB.class).orElseThrow().failEnd = second;
        var failure = assertThrows(ComponentLifecycleException.class,
            () -> holder.evaluate(event(holder.owner(), 0), () -> true, () -> {
            }));
        assertEquals(ComponentLifecycleException.Phase.END_EVAL, failure.phase());
        assertSame(HookC.class, failure.declaration());
        assertSame(first, failure.getCause());
        assertEquals(1, failure.getSuppressed().length);
        assertSame(second, failure.getSuppressed()[0].getCause());
        assertEquals(List.of("A.begin", "B.begin", "C.begin", "C.end", "B.end", "A.end"), calls);
    }

    @Test
    void fatalHookFailurePropagatesAfterEverySuccessfulBorrowIsCleaned() {
        var holder = hooks();
        var fatal = new OutOfMemoryError("fatal-source");
        holder.exact(HookB.class).orElseThrow().failUpdate = fatal;
        assertSame(fatal, assertThrows(OutOfMemoryError.class,
            () -> holder.evaluate(event(holder.owner(), 0), () -> true, () -> holder.update(1, 2, 0, () -> true))));
        assertEquals(List.of("A.begin", "B.begin", "C.begin", "A.update:1.0:2.0:0.0", "B.update:1.0:2.0:0.0", "C.end",
            "B.end", "A.end"), calls);
    }

    @Test
    void fatalCleanupNeverReplacesAnEarlierBodyOrBeginFailure() {
        var bodyHolder = hooks();
        var bodyFailure = new IllegalArgumentException("body-source");
        var bodyCleanup = new OutOfMemoryError("body-cleanup");
        bodyHolder.exact(HookB.class).orElseThrow().failEnd = bodyCleanup;
        var bodyThrown = assertThrows(IllegalArgumentException.class,
            () -> bodyHolder.evaluate(event(bodyHolder.owner(), 0), () -> true, () -> {
                throw bodyFailure;
            }));
        assertSame(bodyFailure, bodyThrown);
        assertArrayEquals(new Throwable[] {bodyCleanup}, bodyThrown.getSuppressed());
        assertEquals(List.of("A.begin", "B.begin", "C.begin", "C.end", "B.end", "A.end"), calls);

        var beginHolder = hooks();
        var beginFailure = new IllegalStateException("begin-source");
        var beginCleanup = new ThreadDeath();
        beginHolder.exact(HookB.class).orElseThrow().failBegin = beginFailure;
        beginHolder.exact(HookA.class).orElseThrow().failEnd = beginCleanup;
        var beginThrown = assertThrows(ComponentLifecycleException.class,
            () -> beginHolder.evaluate(event(beginHolder.owner(), 0), () -> true, () -> fail("body ran")));
        assertSame(beginFailure, beginThrown.getCause());
        assertArrayEquals(new Throwable[] {beginCleanup}, beginThrown.getSuppressed());
        assertEquals(List.of("A.begin", "B.begin", "A.end"), calls);
    }

    @Test
    void resultDomainsKeepBusinessMissingSeparateFromZeroFalseAndEmptyString() {
        var entity = allocate(TestEntity.class);
        setPosition(entity, 0, -0.0d, 4);
        var holder = BuiltinDeclarations.createComponents(TestComponentHolders.owner(entity),
            ExpressionHost.create(HostAvailability.none()));
        var position = holder.exact(PositionComponent.class).orElseThrow();
        {
            var context = readContext((Entity)entity);
            context.bindExpression("component", 0);
            context.beginTopLevel(false);
            try {
                assertEquals(Float.valueOf(0), position.position(context, 0));
                assertEquals(Float.valueOf(-0.0f), position.position(context, 1));
                assertEquals(Float.valueOf(4), position.position(context, 2));
                assertNull(position.position(context, 3));
                assertTrue(Float.isNaN(position.positionDelta(context, -1)));
                assertEquals(Float.valueOf(0), position.positionDelta(context, 0));
                assertNull(ControlBuiltins.hold(context, 2, "mainhand", "", false));
                assertEquals("", new ModelBuiltins(TestComponentHolders.owner((Entity)entity)).modVersion(context, "missing_test_mod"));
            } finally {
                context.endTopLevel();
                context.releaseExpression();
                context.closeRound();
            }
        }
        var living = allocate(ArmorStand.class);
        {
            var context = readContext((Entity)living);
            context.bindExpression("component", 0);
            context.beginTopLevel(false);
            try {
                assertNull(ControlBuiltins.armor(context, 2, "not_a_slot", "", false));
                assertNull(ControlBuiltins.hold(context, 2, "head", "", false));
                assertEquals(Boolean.FALSE, ControlBuiltins.armor(context, 2, "head", "", false));
                assertEquals(Boolean.FALSE, ControlBuiltins.hold(context, 2, "mainhand", "", false));
                assertNull(ControlBuiltins.isItemNameAny(context, 2, "not_a_slot", "item"));
                assertNull(ControlBuiltins.relativeBlockHasAnyTag(context, 4, 6, 0, 0, "tag"));
            } finally {
                context.endTopLevel();
                context.releaseExpression();
                context.closeRound();
            }
        }
        assertFalse(com.elfmcys.ysm.client.compat.curios.CuriosCompat.isInstalled());
        {
            var context = readContext((net.minecraft.world.entity.LivingEntity)living);
            context.bindExpression("component", 0);
            context.beginTopLevel(false);
            try {
                assertNull(CuriosBuiltins.hasAnyCurios(context, 1, ""));
                assertNull(CuriosBuiltins.hasAnyCurios(context, 2, "ring", "invalid id"));
                assertEquals(Boolean.FALSE, CuriosBuiltins.hasAnyCurios(context, 1, "ring"));
            } finally {
                context.endTopLevel();
                context.releaseExpression();
                context.closeRound();
            }
        }
        holder.invalidate();
    }

    @Test
    void compiledPositionDeltaKeepsAxisValuesZeroAndMissingDistinct() throws Throwable {
        var entity = allocate(TestEntity.class);
        var owner = TestComponentHolders.owner(entity);
        var host = ExpressionHost.create(HostAvailability.none());
        var holder = BuiltinDeclarations.createComponents(owner, host);
        setPosition(entity, 2, 3, 4);
        holder.evaluate(event(owner, 1), () -> true, () -> holder.update(0, 0, 1, () -> true));
        setPosition(entity, 2, 6, 0);
        holder.evaluate(event(owner, 1), () -> true, () -> holder.update(0, 1, 1, () -> true));
        assertEquals(new Vec3(0, 3, -4), holder.exact(PositionComponent.class).orElseThrow().delta());

        var codec = dev.semaweave.program.PortableProgramCodecs.v1();
        var hash = ExpressionIdentityHash.SHA_256;
        var unit = dev.semaweave.compilation.CompilationUnit.builder(host.compilationPolicy(), codec, hash);
        int[] axes = {0, 1, 2, -1, 3};
        float[] expected = {0, 3, -4, 7, 7};
        var keys = new dev.semaweave.ProgramKey[axes.length];
        for (int index = 0; index < axes.length; index++) {
            keys[index] =
                new dev.semaweave.ProgramKey(dev.semaweave.program.ReadOnlyBytes.copyOf(new byte[] {(byte)index}));
            var portable = new com.elfmcys.ysm.expression.frontend.MolangFrontend().compile(
                "query.position_delta(" + axes[index] + ") ?? 7",
                com.elfmcys.ysm.expression.frontend.MolangFrontend.SourceMode.EXPRESSION,
                new com.elfmcys.ysm.expression.frontend.MolangFrontend.SourceLocation("position-delta/" + axes[index]))
                .program();
            unit.addProgram(new dev.semaweave.compilation.ProgramSource(keys[index], portable,
                new dev.semaweave.TopLevelEntrySignature(dev.semaweave.ProgramShape.F32,
                    new dev.semaweave.EntryResult.Value(dev.semaweave.BuiltinType.F32), List.of(), List.of()),
                java.util.Optional.empty(), new dev.semaweave.compilation.DiagnosticLocation("axis-" + axes[index])));
        }
        var input = unit.build();
        var plan = host.expressionBinding().bindingPlan();
        var backend = new dev.semaweave.backend.janino.JaninoJavaCompilerBackend();
        var artifact = new dev.semaweave.compilation.DefaultExpressionAotCompiler<MolangContext>(backend).compile(
            dev.semaweave.compilation.TaskContext.NONE, input, host.hostAbi(), host.registry(),
            dev.semaweave.compilation.CompilerOptions.DEFAULT, plan, hash);
        var identity = dev.semaweave.compilation.ExpectedArtifactIdentity.create(input, host.hostAbi(), host.registry(),
            dev.semaweave.compilation.CompilerOptions.DEFAULT, plan, backend.descriptor(), hash);
        try (var resource = dev.semaweave.compilation.ModuleLoaders.<MolangContext>standard().load(artifact, identity,
            host.registry(), host.registry().visibilityRoot(), hash)) {
            var linkage = dev.semaweave.RegistryRuntimeLinkage.<MolangContext>create(holder.providers(host, plan),
                dev.semaweave.RuntimeSymbolTable.empty());
            try (var instance = resource.newInstance(new dev.semaweave.RuntimeLinkage<MolangContext>() {
                public dev.semaweave.ContextType<MolangContext> contextType() {
                    return host.contextType();
                }

                public dev.semaweave.RegistryRuntimeLinkage<MolangContext> registry() {
                    return linkage;
                }

                public dev.semaweave.compilation.BakedConstantTable constants() {
                    return artifact.constants();
                }

                public dev.semaweave.RuntimeDiagnostics diagnostics() {
                    return dev.semaweave.RuntimeDiagnostics.none();
                }

                public dev.semaweave.ResourceLease<MolangContext> retainResource() {
                    return resource.retain();
                }
            })) {
                var subject = instance.bindSubject(entity);
                var context = readContext((Entity)entity);
                context.configureLimits(host.executionLimits());
                try {
                    for (int index = 0; index < axes.length; index++) {
                        var id = resource.programs().requireId(keys[index]);
                        context.bindExpression("position-delta/" + axes[index], id.value());
                        try {
                            float actual = instance.executeFloat(
                                instance.bindProgram(id, dev.semaweave.ProgramShape.F32), context, subject, context);
                            assertEquals(Float.floatToRawIntBits(expected[index]), Float.floatToRawIntBits(actual),
                                "axis=" + axes[index]);
                        } finally {
                            context.releaseExpression();
                        }
                    }
                    assertNull(context.firstFailure());
                } finally {
                    context.closeRound();
                }
            }
        } finally {
            holder.invalidate();
        }
    }

    private static <E extends Entity> MolangContext<E> readContext(E entity) {
        var inputs = new InvocationInputs<E>() {
            public E entity() {
                return entity;
            }

            public void prevalidateEffect(PreparedEffect command, MolangContext<?> scope) {
                throw new AssertionError(command);
            }
        };
        return MolangContext.observation(entity, inputs, new ControlledHostFixture(),
            com.elfmcys.ysm.expression.TestRoamingStates.create(null, dev.semaweave.RoamingRole.PRODUCER),
            new InvocationProvenance("result-domain", "declaration", 0, 1, 1), () -> false, () -> true);
    }

    private static final List<String> calls = new ArrayList<>();

    private static DeclarationComponentScope hooks() {
        calls.clear();
        var owner = TestComponentHolders.owner(allocate(TestEntity.class));
        var template = ExpressionHost.create(HostAvailability.none()).declarations().stream()
            .filter(d -> d.owner() == PositionComponent.class).findFirst().orElseThrow();
        return new DeclarationComponentScope(owner,
            List.of(HookA.class, HookB.class, HookC.class).stream().map(type -> new LinkedDeclaration(template.schema(),
                template.verifiedModId(), type, type, type.getClassLoader(), template.members())).toList());
    }

    private static AnimationEvent<?> event(AnimatableEntity<?> owner, float partial) {
        return new AnimationEvent<>(owner, 10, partial, partial, RenderContext.levelImmutable(), new EntityModelData(),
            null);
    }

    public static class Hook implements EntityComponent<Entity> {
        private final String id;
        Throwable failTarget, failBegin, failTick, failUpdate, failEnd;
        Runnable afterBegin = () -> {
        }, afterTick = () -> {
        };
        ModelRenderTarget target;

        Hook(String id) {
            this.id = id;
        }

        public Hook provider() {
            return this;
        }

        public void setRenderTarget(ModelRenderTarget target) {
            calls.add(id + ".target");
            fail(failTarget);
            this.target = target;
        }

        public void beginEval(AnimationEvent<?> event) {
            calls.add(id + ".begin");
            fail(failBegin);
            afterBegin.run();
        }

        public void tick(int previous, int current) {
            calls.add(id + ".tick:" + previous + ":" + current);
            fail(failTick);
            afterTick.run();
        }

        public void update(float previous, float current, float partial) {
            calls.add(id + ".update:" + previous + ":" + current + ":" + partial);
            fail(failUpdate);
        }

        public void endEval() {
            calls.add(id + ".end");
            fail(failEnd);
        }

        private static void fail(Throwable failure) {
            if (failure instanceof Error error)
                throw error;
            if (failure instanceof RuntimeException runtime)
                throw runtime;
        }
    }

    public static final class HookA extends Hook {
        public HookA(AnimatableEntity<Entity> owner) {
            super("A");
        }
    }

    public static final class HookB extends Hook {
        public HookB(AnimatableEntity<Entity> owner) {
            super("B");
        }
    }

    public static final class HookC extends Hook {
        public HookC(AnimatableEntity<Entity> owner) {
            super("C");
        }
    }

    private static <T> T allocate(Class<T> type) {
        try {
            return type.cast(unsafe.allocateInstance(type));
        } catch (InstantiationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void setPosition(Entity entity, double x, double y, double z) {
        try {
            positionField.set(entity, new Vec3(x, y, z));
        } catch (IllegalAccessException failure) {
            throw new AssertionError(failure);
        }
    }

    private static final class TestEntity extends Entity {
        private TestEntity() {
            super(EntityType.ARMOR_STAND, null);
        }

        @Override
        public float getViewYRot(float partialTick) {
            return 180 * partialTick;
        }

        @Override
        protected void defineSynchedData() {}

        @Override
        protected void readAdditionalSaveData(CompoundTag tag) {}

        @Override
        protected void addAdditionalSaveData(CompoundTag tag) {}
    }
}
