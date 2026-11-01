package com.elfmcys.ysm.expression.aot;

import com.elfmcys.ysm.api.molang.PreparedEffect;
import com.elfmcys.ysm.expression.host.*;
import com.elfmcys.ysm.expression.inventory.MolangEventCatalog;
import com.elfmcys.ysm.geckolib3.core.PlayState;
import com.elfmcys.ysm.expression.inventory.ModelProgramInventory;
import com.elfmcys.ysm.expression.inventory.ProgramRole;
import com.elfmcys.ysm.geckolib3.core.processor.MinecraftRuntimeInputs;
import dev.semaweave.CompiledModuleInstance;
import dev.semaweave.CompiledModuleResource;
import dev.semaweave.ExecutionFrame;
import dev.semaweave.ProgramArguments;
import dev.semaweave.ProgramId;
import dev.semaweave.ProgramShape;
import dev.semaweave.ResourceLease;
import dev.semaweave.RuntimeLinkage;
import dev.semaweave.compilation.BakedConstantTable;
import dev.semaweave.compilation.ProgramCatalog;
import dev.semaweave.compilation.ProgramRef;
import dev.semaweave.compilation.TestResourceGenerations;
import dev.semaweave.compilation.TransientAccessException;
import dev.semaweave.compilation.TransientAccessRequest;
import dev.semaweave.compilation.TransientDynamicAccess;
import dev.semaweave.compilation.TransientExecutionAccess;
import dev.semaweave.compilation.TransientLayout;
import dev.semaweave.program.ReadOnlyBytes;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EntityExpressionRuntimeTest {
    @Test
    void invocationFailureLogsFollowCapturedAnimationDebugState() {
        for (boolean debug : new boolean[]{false, true}) {
            var script = new Script();
            var harness = harness(script);
            try (var runtime = new EntityExpressionRuntime(); var module = harness.module;
                 var logs = new com.elfmcys.ysm.testutil.LogCapture(com.elfmcys.ysm.YesSteveModel.LOGGER)) {
                TestExpressionModules.replace(runtime, module, harness.events);
                var event = new com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent<>(null, 0, 0, 0,
                        com.elfmcys.ysm.geckolib3.geo.RenderContext.levelImmutable(),
                        new com.elfmcys.ysm.geckolib3.model.provider.data.EntityModelData(),
                        debug ? com.elfmcys.ysm.client.animation.debug.CustomDebugSource.INSTANCE : null);
                var production = new MinecraftRuntimeInputs<net.minecraft.world.entity.Entity>() {
                    @Override
                    public com.elfmcys.ysm.client.entity.AnimatableEntity<?> animatable() {
                        return null;
                    }

                    @Override
                    public com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent<?> animationEvent() {
                        return event;
                    }

                    @Override
                    public net.minecraft.util.RandomSource randomSource() {
                        return null;
                    }

                    @Override
                    public com.elfmcys.ysm.expression.host.ControllerExecutionState controllerState() {
                        return null;
                    }

                    @Override
                    public com.elfmcys.ysm.expression.host.AnimationExecutionState animationState() {
                        return null;
                    }

                    @Override
                    public void prevalidateEffect(PreparedEffect command, MolangContext<?> scope) {
                    }
                };
                var inputs =
                        new EntityExpressionRuntime.InvocationInputs(production, new RecordingEffects(), () -> false);
                script.failCoded = true;
                assertNull(runtime.update(inputs, evaluation -> evaluation.codedController(harness.coded)));
                var failures = logs.events().stream().filter(log -> log.getMessage().getFormattedMessage()
                        .startsWith("Expression invocation aborted without disabling its program:")).toList();
                assertEquals(1, failures.size());
                assertEquals(debug ? org.apache.logging.log4j.Level.WARN : org.apache.logging.log4j.Level.DEBUG,
                        failures.get(0).getLevel());
                assertEquals("provider failure", failures.get(0).getThrown().getMessage());
                script.failCoded = false;
                script.coded = 2;
                assertEquals(PlayState.CONTINUE,
                        runtime.update(inputs, evaluation -> evaluation.codedController(harness.coded)));
            }
        }
    }

    private static volatile Object allocationPose;

    @Test
    void literalPoseSamplingReportsAllocationSeparatelyFromScalarExecution() {
        var script = new Script();
        var harness = harness(script);
        var begin = new com.elfmcys.ysm.geckolib3.core.keyframe.bone.Vector3v(new FloatExpression.Literal(1), new FloatExpression.Literal(2), new FloatExpression.Literal(3));
        var end = new com.elfmcys.ysm.geckolib3.core.keyframe.bone.Vector3v(new FloatExpression.Literal(3), new FloatExpression.Literal(4), new FloatExpression.Literal(5));
        var keyframe = new com.elfmcys.ysm.geckolib3.core.keyframe.bone.LinearKeyFrame(0, 1, begin, end, end);
        try (var runtime = new EntityExpressionRuntime(); var module = harness.module) {
            TestExpressionModules.replace(runtime, module, harness.events);
            runtime.observe(inputs(new RecordingEffects()), evaluation -> {
                var threads = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
                for (int pass = 0; pass < 2; pass++) {
                    long before = threads.getThreadAllocatedBytes(Thread.currentThread().getId());
                    for (int sample = 0; sample < 50000; sample++) {
                        var point = new com.elfmcys.ysm.geckolib3.core.keyframe.point.KeyFramePoint(0.5f, keyframe);
                        var value = point.getLerpPoint(evaluation);
                        if (value.x != 2 || value.y != 3 || value.z != 4) throw new AssertionError("pose oracle");
                        allocationPose = point;
                    }
                    long bytes = threads.getThreadAllocatedBytes(Thread.currentThread().getId()) - before;
                    if (pass == 1)
                        System.out.println("POSE_ALLOCATION iterations=50000 allocatedBytes=" + bytes + " bytesPerSample=" + (bytes / 50000.0));
                }
                return null;
            });
        }
    }

    private static final Inputs INPUTS = new Inputs();

    @Test
    void consecutiveEntriesShareTheRoundContextAndTheNextRoundGetsAnother() {
        var script = new Script();
        var harness = harness(script);
        try (var runtime = new EntityExpressionRuntime(); var module = harness.module) {
            TestExpressionModules.replace(runtime, module, harness.events);
            runtime.update(inputs(new RecordingEffects()), evaluation -> {
                for (int index = 0; index < 128; index++)
                    evaluation.evaluate(harness.sample);
                return null;
            });
            assertEquals(128, script.contexts.size());
            var first = script.contexts.get(0);
            assertTrue(script.contexts.stream().allMatch(value -> value == first));
            assertFalse(first.isActive());
            assertNull(first.entityView());
            float result =
                    runtime.observe(inputs(new RecordingEffects()), evaluation -> evaluation.evaluate(harness.sample));
            assertEquals(129f, result);
            assertTrue(first != script.contexts.get(128));
        }
    }

    @Test
    void invalidProjectionIsRejectedBeforeBorrowingTheRound() {
        var harness = harness(new Script());
        var inputs = inputs(new RecordingEffects());
        try (var runtime = new EntityExpressionRuntime(); var module = harness.module) {
            TestExpressionModules.replace(runtime, module, harness.events);
            assertThrows(NullPointerException.class, () -> runtime.normalUpdate(inputs, evaluation -> {
                throw new AssertionError("invalid projection reached the body");
            }, null));
            float first = runtime.observe(inputs, evaluation -> evaluation.evaluate(harness.sample));
            assertEquals(1f, first);
            assertThrows(NullPointerException.class, () -> runtime.animationPass(inputs, true, evaluation -> {
                throw new AssertionError("invalid projection reached the body");
            }, null));
            float second = runtime.update(inputs, evaluation -> evaluation.evaluate(harness.sample));
            assertEquals(2f, second);
            float observation =
                    runtime.animationPass(inputs, false, evaluation -> {
                        evaluation.dispatchPendingDeferred();
                        return evaluation.evaluate(harness.sample);
                    }, null);
            assertEquals(3f, observation);
        }
    }

    @Test
    void failedEndHookDiscardsTheWholeAnimationPassBeforePublishing() {
        var script = new Script();
        script.stageCodedEffect = true;
        var harness = harness(script);
        var effects = new RecordingEffects();
        var hookFailure = new IllegalStateException("end hook failure");
        try (var runtime = new EntityExpressionRuntime(); var module = harness.module) {
            TestExpressionModules.replace(runtime, module, harness.events);
            assertSame(hookFailure, assertThrows(IllegalStateException.class,
                    () -> runtime.animationPass(inputs(effects), true, evaluation -> {
                        evaluation.evaluate(harness.coded);
                        evaluation.evaluate(harness.sample);
                        assertEquals(0, effects.commits.get());
                        throw hookFailure;
                    }, delta -> {
                        throw new AssertionError("failed pass projection");
                    })));
            assertEquals(0, effects.commands.get());
            assertEquals(0, effects.commits.get());
            runtime.animationPass(inputs(effects), true, evaluation -> {
                evaluation.evaluate(harness.coded);
                evaluation.evaluate(harness.sample);
                assertEquals(0, effects.commits.get());
                return null;
            }, delta -> {
            });
            assertEquals(1, effects.commands.get());
            assertEquals(1, effects.commits.get());
        }
    }

    @Test
    void failedRoundPreservesPrimaryFailureAndReleasesTheOwner() {
        var harness = harness(new Script());
        var primary = new IllegalStateException("body failure");
        var effects = new RecordingEffects();
        try (var runtime = new EntityExpressionRuntime(); var module = harness.module) {
            TestExpressionModules.replace(runtime, module, harness.events);
            assertSame(primary, assertThrows(IllegalStateException.class,
                () -> runtime.update(inputs(effects), evaluation -> { throw primary; })));
            assertEquals(0, effects.commits.get());
            float next = runtime.update(inputs(effects), evaluation -> evaluation.evaluate(harness.sample));
            assertEquals(1f, next);
            assertEquals(1, effects.commits.get());
        }
    }

    @Test
    void dispatchesClosedEventsInOrderAndSharesOnlyPerEntityInstanceState() {
        var script = new Script();
        var harness = harness(script);
        var first = new EntityExpressionRuntime();
        var second = new EntityExpressionRuntime();
        TestExpressionModules.replace(first, harness.module, harness.events);
        TestExpressionModules.replace(second, harness.module, harness.events);
        var firstEffects = new RecordingEffects();
        var secondEffects = new RecordingEffects();

        var firstValues = first.update(inputs(firstEffects), evaluation -> {
            evaluation.dispatchPlayerInit();
            evaluation.dispatchPlayerUpdate(true);
            evaluation.dispatchSync(EventArguments.sync(1, 2));
            return List.of(evaluation.execute(harness.dualCallable, ProgramArguments.empty()),
                    evaluation.evaluate(harness.sample), evaluation.evaluate(harness.sample));
        });
        var secondValue = second.update(inputs(secondEffects), evaluation -> evaluation.evaluate(harness.sample));

        assertEquals(List.of(1.0f, 2.0f, 3.0f), firstValues);
        assertEquals(1.0f, secondValue);
        assertEquals(List.of("init", "update:true", "sync:1.0,2.0"), script.events);
        assertEquals(1, firstEffects.commits.get());
        assertEquals(1, secondEffects.commits.get());

        first.close();
        second.close();
        harness.module.close();
        assertEquals(0, harness.resource.holds.get());
    }

    @Test
    void appliesOnlyExactCodedControllerResultsAndDiscardsFailedLogicalBatch() {
        var script = new Script();
        var harness = harness(script);
        var runtime = new EntityExpressionRuntime();
        TestExpressionModules.replace(runtime, harness.module, harness.events);
        var effects = new RecordingEffects();

        for (var value : new float[]{2, 3, 4}) {
            script.coded = value;
            var coded = runtime.update(inputs(effects), evaluation -> evaluation.codedController(harness.coded));
            assertEquals(value == 2 ? PlayState.CONTINUE : value == 3 ? PlayState.STOP : PlayState.PAUSE, coded);
        }
        for (var value : new float[]{5, 0, Float.NaN}) {
            script.coded = value;
            assertNull(runtime.update(inputs(effects), evaluation -> evaluation.codedController(harness.coded)));
        }
        assertNull(runtime.update(inputs(effects), evaluation -> evaluation.codedController(null)));

        script.stageCodedEffect = true;
        script.coded = 5;
        assertNull(runtime.update(inputs(effects), evaluation -> evaluation.codedController(harness.coded)));
        assertEquals(1, effects.commands.get());
        script.failCoded = true;
        assertNull(runtime.update(inputs(effects), evaluation -> evaluation.codedController(harness.coded)));
        script.failCoded = false;
        script.stageCodedEffect = false;
        script.coded = 2;
        assertEquals(PlayState.CONTINUE,
                runtime.update(inputs(effects), evaluation -> evaluation.codedController(harness.coded)));

        assertEquals(9, effects.commits.get());
        assertEquals(1, effects.commands.get());
        assertEquals(9, script.codedCalls);
        runtime.close();
        harness.module.close();
    }

    @Test
    void cancelledCodedControllerDelegatesThenRecoversOnTheNextInvocation() {
        var script = new Script();
        var harness = harness(script);
        var runtime = new EntityExpressionRuntime();
        TestExpressionModules.replace(runtime, harness.module, harness.events);
        var effects = new RecordingEffects();
        var cancelled = new AtomicBoolean(true);
        script.coded = 2;

        assertNull(
                runtime.update(inputs(effects, cancelled::get), evaluation -> evaluation.codedController(harness.coded)));
        assertEquals(0, effects.commits.get());
        cancelled.set(false);
        assertEquals(PlayState.CONTINUE,
                runtime.update(inputs(effects, cancelled::get), evaluation -> evaluation.codedController(harness.coded)));

        assertEquals(1, script.codedCalls);
        assertEquals(1, effects.commits.get());
        runtime.close();
        harness.module.close();
    }

    @Test
    void laterSuccessfulZeroFalseAndCodedResultsCannotClearTheEvaluationFailure() {
        var script = new Script();
        var harness = harness(script);
        var runtime = new EntityExpressionRuntime();
        TestExpressionModules.replace(runtime, harness.module, harness.events);
        var effects = new RecordingEffects();
        script.stageCodedEffect = true;
        runtime.update(inputs(effects), evaluation -> {
            script.failCoded = true;
            assertNull(evaluation.codedController(harness.coded));
            var failure = evaluation.failure().orElseThrow();
            script.failCoded = false;
            script.coded = 0;
            assertEquals(0f, evaluation.evaluate(harness.coded));
            assertFalse(evaluation
                    .evaluate(new BooleanProgram(location(ProgramRole.CONTROLLER_TRANSITION, "controller/predicate", ""),
                            ref(9, ProgramShape.BOOLEAN), harness.sample.generation())));
            script.coded = 2;
            assertEquals(PlayState.CONTINUE, evaluation.codedController(harness.coded));
            assertEquals(failure, evaluation.failure().orElseThrow());
            return null;
        });
        assertEquals(0, effects.commits.get());
        assertEquals(0, effects.commands.get());
        assertEquals(PlayState.CONTINUE, runtime.update(inputs(effects), evaluation -> {
            assertTrue(evaluation.failure().isEmpty());
            return evaluation.codedController(harness.coded);
        }));
        assertEquals(1, effects.commits.get());
        runtime.close();
        harness.module.close();
    }

    @Test
    void cyclicOrdinaryFailureDelegatesWithoutCommittingAndAllowsTheNextInvocation() {
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            var script = new Script();
            var harness = harness(script);
            var runtime = new EntityExpressionRuntime();
            TestExpressionModules.replace(runtime, harness.module, harness.events);
            var effects = new RecordingEffects();
            var first = new IllegalStateException("outer failure");
            var second = new IllegalArgumentException("inner failure", first);
            first.initCause(second);
            script.codedFailure = first;
            script.stageCodedEffect = true;
            try {
                runtime.update(inputs(effects), evaluation -> {
                    assertNull(evaluation.codedController(harness.coded));
                    assertSame(first, evaluation.failure().orElseThrow());
                    return null;
                });
                assertEquals(0, effects.commits.get());
                assertEquals(0, effects.commands.get());
                script.codedFailure = null;
                script.coded = 2;
                assertEquals(PlayState.CONTINUE, runtime.update(inputs(effects), evaluation -> {
                    assertTrue(evaluation.failure().isEmpty());
                    return evaluation.codedController(harness.coded);
                }));
                assertEquals(1, effects.commits.get());
                assertEquals(1, effects.commands.get());
            } finally {
                runtime.close();
                harness.module.close();
            }
        });
    }

    @Test
    void budgetFailureDelegatesThenRecoversOnTheNextInvocation() {
        var script = new Script();
        var harness = harness(script);
        var runtime = new EntityExpressionRuntime();
        TestExpressionModules.replace(runtime, harness.module, harness.events);
        var effects = new RecordingEffects();
        script.coded = 2;
        script.providerCharges = harness.module.executionLimits().maxProviderCalls() + 1;

        assertNull(runtime.update(inputs(effects), evaluation -> evaluation.codedController(harness.coded)));
        assertEquals(0, effects.commits.get());
        script.providerCharges = 0;
        assertEquals(PlayState.CONTINUE,
                runtime.update(inputs(effects), evaluation -> evaluation.codedController(harness.coded)));

        assertEquals(2, script.codedCalls);
        assertEquals(1, effects.commits.get());
        runtime.close();
        harness.module.close();
    }

    @Test
    void replacementWaitsForEligibleInvocationThenRejectsOldBindings() throws Exception {
        var oldScript = new Script();
        var oldHarness = harness(oldScript);
        var newHarness = harness(new Script());
        var runtime = new EntityExpressionRuntime();
        TestExpressionModules.replace(runtime, oldHarness.module, oldHarness.events);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        oldScript.entered = entered;
        oldScript.release = release;
        var oldEffects = new RecordingEffects();

        var executor = Executors.newFixedThreadPool(2);
        try {
            var invocation = executor
                    .submit(() -> runtime.update(inputs(oldEffects), evaluation -> evaluation.evaluate(oldHarness.sample)));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var replacement =
                    executor.submit(() -> TestExpressionModules.replace(runtime, newHarness.module, newHarness.events));
            assertFalse(replacement.isDone());
            release.countDown();
            assertEquals(1.0f, invocation.get(5, TimeUnit.SECONDS));
            replacement.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        var newEffects = new RecordingEffects();
        float stale = runtime.update(inputs(newEffects), evaluation -> evaluation.evaluate(oldHarness.sample));
        assertEquals(0.0f, stale);
        assertEquals(1, oldEffects.commits.get());
        assertEquals(0, newEffects.commits.get());

        runtime.close();
        oldHarness.module.close();
        newHarness.module.close();
    }

    @Test
    void drainsDeferredCapturesLastFirstAndHandlersInDeclaredOrder() {
        var script = new Script();
        var harness = harness(script);
        var runtime = new EntityExpressionRuntime();
        TestExpressionModules.replace(runtime, harness.module, harness.events);
        var effects = new RecordingEffects();

        runtime.update(inputs(effects), evaluation -> {
            evaluation.execute(harness.stageDeferred);
            evaluation.execute(harness.stageDeferred);
            return null;
        });
        assertEquals(List.of("defer-b:2.0", "defer-a:2.0", "defer-b:1.0", "defer-a:1.0"), script.events);
        assertEquals(0, runtime.pendingDeferredCount());
        assertEquals(1, effects.commits.get());
        runtime.update(inputs(effects), evaluation -> null);
        assertEquals(4, script.events.size());

        runtime.close();
        harness.module.close();
    }

    @Test
    void deferredHandlersRunAtTheCapturedAnimationBoundaryBeforeCommit() {
        var script = new Script();
        script.stageDeferredEffect = true;
        var harness = harness(script);
        var effects = new RecordingEffects();
        try (var runtime = new EntityExpressionRuntime(); var module = harness.module) {
            TestExpressionModules.replace(runtime, module, harness.events);
            runtime.update(inputs(effects), evaluation -> {
                var first = new com.elfmcys.ysm.expression.host.AnimationExecutionState(); first.setAnimTime(1);
                var second = new com.elfmcys.ysm.expression.host.AnimationExecutionState(); second.setAnimTime(2);
                evaluation.setAnimationContext(first);
                evaluation.execute(harness.stageDeferred);
                assertTrue(script.events.isEmpty());
                evaluation.setAnimationContext(second);
                assertEquals(List.of(1f, 1f), script.deferredTimes);
                assertEquals(0, effects.commands.get());
                evaluation.execute(harness.stageDeferred);
                evaluation.endAnimationContext();
                assertEquals(List.of(1f, 1f, 2f, 2f), script.deferredTimes);
                evaluation.evaluate(harness.sample);
                return null;
            });
            assertEquals(4, effects.commands.get());
            assertEquals(0, runtime.pendingDeferredCount());
        }
    }

    @Test
    void deferredFailureDiscardsEarlierAndHandlerEffectsAndReleasesTheRound() {
        var script = new Script();
        script.stageCodedEffect = true;
        script.stageDeferredEffect = true;
        script.failDeferred = true;
        var harness = harness(script);
        var effects = new RecordingEffects();
        try (var runtime = new EntityExpressionRuntime(); var module = harness.module) {
            TestExpressionModules.replace(runtime, module, harness.events);
            runtime.update(inputs(effects), evaluation -> {
                evaluation.evaluate(harness.coded);
                evaluation.execute(harness.stageDeferred);
                return null;
            });
            assertEquals(List.of("defer-b:1.0", "defer-a:1.0"), script.events);
            assertEquals(0, effects.commands.get());
            assertEquals(0, effects.commits.get());
            assertEquals(0, runtime.pendingDeferredCount());
            float next = runtime.update(inputs(effects), evaluation -> evaluation.evaluate(harness.sample));
            assertEquals(1f, next);
        }
    }

    @Test
    void transientCapturesDrainBeforeHooksInANonTickAnimationPass() {
        var script = new Script();
        script.stageDeferredEffect = true;
        var harness = harness(script);
        var effects = new RecordingEffects();
        try (var runtime = new EntityExpressionRuntime(); var module = harness.module) {
            TestExpressionModules.replace(runtime, module, harness.events);
            runtime.animationPass(inputs(effects), false, evaluation -> {
                evaluation.evaluate(harness.sample);
                var context = script.contexts.get(0);
                // Use the same explicit action window that an admitted transient session binds.
                context.bindExpression("transient-capture", -1, true);
                context.beginTopLevel(true);
                try {
                    context.submit(new com.elfmcys.ysm.expression.host.DeferredRequest(
                        EventArguments.deferredFloats(1)));
                } finally { context.endTopLevel(); context.releaseExpression(); }
                evaluation.dispatchPendingDeferred();
                assertEquals(List.of("defer-b:1.0", "defer-a:1.0"), script.events);
                assertEquals(0, effects.commits.get());
                return null;
            }, null);
            assertEquals(2, effects.commands.get());
            assertEquals(2, effects.commits.get());
        }
    }

    @Test
    void recursiveDeferredRegistrationUsesOneCumulativeRoundLimit() {
        var script = new Script();
        script.recurseDeferred = true;
        var harness = harness(script);
        var effects = new RecordingEffects();
        try (var runtime = new EntityExpressionRuntime(); var module = harness.module) {
            TestExpressionModules.replace(runtime, module, harness.events);
            runtime.update(inputs(effects), evaluation -> { evaluation.execute(harness.stageDeferred); return null; });
            assertTrue(script.events.size() <= 2 * EntityExpressionRuntime.MAX_DEFERRED_CAPTURES);
            assertTrue(script.stagedDeferred > EntityExpressionRuntime.MAX_DEFERRED_CAPTURES);
            assertEquals(0, effects.commits.get());
            assertEquals(0, runtime.pendingDeferredCount());
        }
    }

    @Test
    void memoryFailureAbortsTheWholeRoundWithoutDisablingTheProgram() {
        var memory = new OutOfMemoryError("injected");
        for (Throwable failure : new Throwable[] {memory}) {
            var script = new Script();
            var harness = harness(script);
            var effects = new RecordingEffects();
            try (var runtime = new EntityExpressionRuntime(); var module = harness.module) {
                TestExpressionModules.replace(runtime, module, harness.events);
                script.codedFailure = failure;
                script.stageCodedEffect = true;
                assertSame(memory, assertThrows(OutOfMemoryError.class, () -> runtime.update(inputs(effects), evaluation -> {
                    evaluation.codedController(harness.coded);
                    org.junit.jupiter.api.Assertions.fail("The failed round must not continue with a default value");
                    return null;
                })));
                assertEquals(0, effects.commits.get());
                script.codedFailure = null;
                script.coded = 2;
                assertEquals(PlayState.CONTINUE, runtime.update(inputs(effects), evaluation ->
                    evaluation.codedController(harness.coded)));
                assertEquals(1, effects.commits.get());
            }
        }
    }

    private static Harness harness(Script script) {
        var host = ExpressionHost.create(HostAvailability.none());
        var resource = new ScriptedResource(script);
        var init = location(ProgramRole.EVENT_PLAYER_INIT, "function/init", "player_init:0");
        var update = location(ProgramRole.EVENT_PLAYER_UPDATE, "function/update", "player_update:0");
        var sync = location(ProgramRole.EVENT_SYNC, "function/sync", "sync:0");
        var deferA = location(ProgramRole.EVENT_DEFER, "function/defer-a", "defer:0");
        var deferB = location(ProgramRole.EVENT_DEFER, "function/defer-b", "defer:1");
        var coded = location(ProgramRole.EVENT_CODED_CONTROLLER, "function/coded", "main_ctrl_main:0");
        var sample = location(ProgramRole.ANIMATION_SAMPLE, "animation/sample", "");
        var stage = location(ProgramRole.ANIMATION_INSTRUCTION, "animation/defer", "0");
        var dualCallable = location(ProgramRole.USER_FUNCTION, "function/dual@player_update", "callable");
        var initRef = ref(0, ProgramShape.VOID);
        var updateRef = ref(1, ProgramShape.VOID);
        var syncRef = ref(2, ProgramShape.VOID);
        var deferARef = ref(3, ProgramShape.VOID);
        var deferBRef = ref(4, ProgramShape.VOID);
        var codedRef = ref(5, ProgramShape.F32);
        var sampleRef = ref(6, ProgramShape.F32);
        var stageRef = ref(7, ProgramShape.VOID);
        var dualCallableRef = ref(8, ProgramShape.USER_FUNCTION);
        var programs = Map.of(init, initRef, update, updateRef, sync, syncRef, deferA, deferARef, deferB, deferBRef,
                coded, codedRef, sample, sampleRef, stage, stageRef, dualCallable, dualCallableRef);
        var module = new RenderTargetExpressionModule(TestExpressionModules.IDENTITY, resource, host,
                new BakedConstantTable(List.of(), ReadOnlyBytes.copyOf(new byte[0])), TestExpressionModules.emptyReport(),
                Map.of(), programs, Map.of(), ExpressionArtifactStore.Source.COLD, MolangEventCatalog.production(),
                HostLinkage.production(host));
        var generation = resource.generation();
        var events = Map.<String, List<EventProgram>>of("player_init",
                List.of(new VoidProgram(init, initRef, generation)), "player_update",
                List.of(new VoidProgram(update, updateRef, generation)), "sync",
                List.of(new VoidProgram(sync, syncRef, generation)), "defer",
                List.of(new VoidProgram(deferB, deferBRef, generation), new VoidProgram(deferA, deferARef, generation)));
        return new Harness(module, resource, events, new FloatExpression.Program(coded, codedRef, generation),
                new FloatExpression.Program(sample, sampleRef, generation), new VoidProgram(stage, stageRef, generation),
                new UserFunctionProgram(dualCallable, dualCallableRef, generation));
    }

    private static ModelProgramInventory.SemanticLocation location(ProgramRole role, String location,
                                                                   String discriminator) {
        return new ModelProgramInventory.SemanticLocation("test", location, role, discriminator);
    }

    private static ProgramRef ref(int id, ProgramShape shape) {
        return TestExpressionModules.reference(id, shape, null);
    }

    private static EntityExpressionRuntime.InvocationInputs inputs(RecordingEffects effects) {
        return inputs(effects, () -> false);
    }

    private static EntityExpressionRuntime.InvocationInputs inputs(RecordingEffects effects,
                                                                   java.util.function.BooleanSupplier cancellation) {
        return new EntityExpressionRuntime.InvocationInputs(INPUTS, effects, cancellation);
    }

    private record Harness(RenderTargetExpressionModule module, ScriptedResource resource,
                           Map<String, List<EventProgram>> events, FloatExpression.Program coded,
                           FloatExpression.Program sample,
                           VoidProgram stageDeferred, UserFunctionProgram dualCallable) {
    }

    private static final class Script {
        private final List<String> events = new ArrayList<>();
        private final List<MolangContext> contexts = new ArrayList<>();
        private volatile float coded;
        private volatile boolean failCoded;
        private volatile Throwable codedFailure;
        private volatile boolean stageCodedEffect;
        private int providerCharges;
        private int codedCalls;
        private volatile CountDownLatch entered;
        private volatile CountDownLatch release;
        private int stagedDeferred;
        private boolean stageDeferredEffect, failDeferred, recurseDeferred;
        private final List<Float> deferredTimes = new ArrayList<>();
    }

    private static dev.semaweave.SubjectBinding testBinding(Object subject,
                                                            dev.semaweave.compilation.ResourceGenerationToken generation) {
        return new dev.semaweave.SubjectBinding() {
            public Object subject() {
                return subject;
            }

            public dev.semaweave.compilation.ResourceGenerationToken generation() {
                return generation;
            }
        };
    }

    private static final class ScriptedResource implements CompiledModuleResource<MolangContext> {
        private final Script script;
        private final dev.semaweave.compilation.ResourceGenerationToken generation = TestResourceGenerations.create();
        private final AtomicInteger holds = new AtomicInteger(1);

        private ScriptedResource(Script script) {
            this.script = script;
        }

        @Override
        public dev.semaweave.compilation.CompiledArtifactIdentity identity() {
            return TestExpressionModules.IDENTITY.expected().identity();
        }

        @Override
        public dev.semaweave.SubjectBinding bindSubject(Object subject) {
            return testBinding(subject, generation);
        }

        @Override
        public ProgramRef bindProgram(ProgramId source, ProgramShape shape) {
            return TestExpressionModules.reference(source.value(), shape, generation);
        }

        @Override
        public ProgramCatalog programs() {
            return null;
        }

        @Override
        public dev.semaweave.compilation.ResourceGenerationToken generation() {
            return generation;
        }

        @Override
        public TransientLayout<MolangContext> createTransientLayout(TransientAccessRequest request)
                throws TransientAccessException {
            throw new UnsupportedOperationException();
        }

        @Override
        public ResourceLease<MolangContext> retain() {
            holds.incrementAndGet();
            return new Lease(holds, generation);
        }

        @Override
        public dev.semaweave.CompiledRoamingState newRoamingState(dev.semaweave.RoamingRole role) {
            return com.elfmcys.ysm.expression.TestRoamingStates.create(generation, role);
        }

        @Override
        public CompiledModuleInstance<MolangContext> newInstance(RuntimeLinkage<MolangContext> linkage) {
            return new ScriptedInstance(script, linkage.retainResource());
        }

        @Override
        public CompiledModuleInstance<MolangContext> newInstance(RuntimeLinkage<MolangContext> linkage,
                                                                 dev.semaweave.CompiledRoamingState roamingState) {
            return new ScriptedInstance(script, linkage.retainResource());
        }

        @Override
        public void close() {
            holds.decrementAndGet();
        }
    }

    private static final class Lease implements ResourceLease<MolangContext> {
        private final AtomicInteger holds;
        private boolean closed;
        private final dev.semaweave.compilation.ResourceGenerationToken generation;

        private Lease(AtomicInteger holds, dev.semaweave.compilation.ResourceGenerationToken generation) {
            this.holds = holds;
            this.generation = generation;
        }

        @Override
        public ResourceLease<MolangContext> retain() {
            holds.incrementAndGet();
            return new Lease(holds, generation);
        }

        @Override
        public dev.semaweave.SubjectBinding bindSubject(Object subject) {
            return testBinding(subject, generation);
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                holds.decrementAndGet();
            }
        }
    }

    private static final class ScriptedInstance implements CompiledModuleInstance<MolangContext> {
        @Override
        public ProgramRef bindProgram(ProgramId source, ProgramShape shape) {
            return TestExpressionModules.reference(source.value(), shape, null);
        }
        private final Script script;
        private final ResourceLease<MolangContext> lease;
        private int ordinaryState;

        private ScriptedInstance(Script script, ResourceLease<MolangContext> lease) {
            this.script = script;
            this.lease = lease;
        }

        @Override
        public dev.semaweave.compilation.CompiledArtifactIdentity identity() {
            return TestExpressionModules.IDENTITY.expected().identity();
        }

        @Override
        public TransientExecutionAccess<MolangContext> openTransientAccess(TransientLayout<MolangContext> layout,
                                                                           TransientDynamicAccess dynamicAccess) {
            throw new UnsupportedOperationException();
        }

        @Override
        public dev.semaweave.SubjectBinding bindSubject(Object subject) {
            return lease.bindSubject(subject);
        }

        @Override
        public Object executeValue(ProgramRef id, MolangContext context, dev.semaweave.SubjectBinding binding,
                                   ExecutionFrame frame, ProgramArguments arguments) {
            frame.beginTopLevel(false);
            try {
                if (id.sourceId() != 8)
                    throw new AssertionError(id);
                return (float) ordinaryState;
            } finally {
                frame.endTopLevel();
            }
        }

        @Override
        public float executeFloat(ProgramRef id, MolangContext context, dev.semaweave.SubjectBinding binding,
                                  ExecutionFrame frame, ProgramArguments arguments) throws Throwable {
            assertSame(context, frame);
            script.contexts.add(context);
            frame.beginTopLevel(id.sourceId() == 5);
            try {
                if (id.sourceId() == 5) {
                    script.codedCalls++;
                    for (int index = 0; index < script.providerCharges; index++) {
                        frame.chargeProviderCall();
                    }
                    if (script.stageCodedEffect) {
                        context.submit((PreparedEffect)() -> {});
                    }
                    if (script.failCoded)
                        throw new IllegalStateException("provider failure");
                    if (script.codedFailure != null)
                        throw script.codedFailure;
                    return script.coded;
                }
                if (script.entered != null) {
                    script.entered.countDown();
                    script.release.await(5, TimeUnit.SECONDS);
                }
                return ++ordinaryState;
            } finally {
                frame.endTopLevel();
            }
        }

        @Override
        public boolean executeBoolean(ProgramRef id, MolangContext context, dev.semaweave.SubjectBinding binding,
                                      ExecutionFrame frame, ProgramArguments arguments) {
            frame.beginTopLevel(false);
            try {
                return false;
            } finally {
                frame.endTopLevel();
            }
        }

        @Override
        public void executeVoid(ProgramRef id, MolangContext context, dev.semaweave.SubjectBinding binding,
                                ExecutionFrame frame, ProgramArguments arguments) {
            frame.beginTopLevel(true);
            try {
                switch (id.sourceId()) {
                    case 0 -> script.events.add("init");
                    case 1 -> {
                        ordinaryState++;
                        script.events.add("update:" + arguments.booleanValue(0));
                    }
                    case 2 -> {
                        var values = (EventArguments) arguments.referenceValue(0);
                        script.events.add("sync:" + values.getFloat(0) + "," + values.getFloat(1));
                    }
                    case 3, 4 -> {
                        var values = (EventArguments) arguments.referenceValue(0);
                        script.events.add("defer-" + (id.sourceId() == 3 ? "a:" : "b:") + values.getFloat(0));
                        assertTrue(context.isActive()); assertTrue(context.actionAllowed());
                        var animation = context.inputs().animation();
                        script.deferredTimes.add(animation == null ? -1 : animation.time());
                        if (script.stageDeferredEffect) context.submit((PreparedEffect)() -> {});
                        if (script.failDeferred && id.sourceId() == 3) throw new IllegalStateException("deferred handler");
                        if (script.recurseDeferred)
                            context.submit(new DeferredRequest(EventArguments.deferredFloats(++script.stagedDeferred)));
                    }
                    case 7 -> context.submit(new DeferredRequest(EventArguments.deferredFloats(++script.stagedDeferred)));
                    default -> throw new AssertionError(id);
                }
            } finally {
                frame.endTopLevel();
            }
        }

        @Override
        public void close() {
            lease.close();
        }
    }

    private static final class RecordingEffects implements EffectSink {
        private final AtomicInteger commits = new AtomicInteger();
        private final AtomicInteger commands = new AtomicInteger();

        @Override
        public PreparedCommit prepare(List<PreparedEffect> commands, InvocationProvenance provenance) {
            this.commands.addAndGet(commands.size());
            return commits::incrementAndGet;
        }
    }

    private static final class Inputs implements InvocationInputs<net.minecraft.world.entity.Entity> {
        @Override
        public void prevalidateEffect(PreparedEffect command, MolangContext<?> scope) {
        }
    }
}
