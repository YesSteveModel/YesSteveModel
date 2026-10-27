package com.elfmcys.ysm.expression.host;

import dev.semaweave.RuntimeValue;
import dev.semaweave.RuntimeValues;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HostLinkageTest {
    @Test
    void manifestClosesBothDirectionsOverExactAvailability() {
        var host = ExpressionHost.create(HostAvailability.all());
        var first = HostLinkage.production(host);
        var second = HostLinkage.production(host);

        first.requireCompatible(host);
        assertEquals(first.manifest(), second.manifest());
        assertEquals(first.fingerprint(), second.fingerprint());
        assertEquals(HostSymbol.values().length + 2, first.manifest().size());
        assertEquals(HostSymbol.values().length,
            first.manifest().stream().map(HostLinkage.Binding::symbol).distinct().count());
        assertEquals(host.manifest().stream().filter(ExpressionHost.ManifestEntry::available).count(),
            first.descriptorManifest().size());
        assertTrue(first.manifest().stream().noneMatch(value -> value.adapter().contains("ExpressionEvaluator")
            || value.adapter().contains("ControlledHostFixture")));

        var absentHost = ExpressionHost.create(HostAvailability.none());
        var absent = HostLinkage.production(absentHost);
        assertTrue(absent.manifest().size() < first.manifest().size());
        assertNotEquals(absent.fingerprint(), first.fingerprint());
    }

    @Test
    void missingOrAbiMismatchedBindingFailsBeforeAnInvocationCanExist() {
        var host = ExpressionHost.create(HostAvailability.all());
        var linkage = HostLinkage.production(host);

        assertThrows(IllegalArgumentException.class,
            () -> linkage.without(HostSymbol.GLOBAL_O).requireCompatible(host));
        assertThrows(IllegalArgumentException.class,
            () -> linkage.withAbiVersion(HostLinkage.ABI_VERSION + 1).requireCompatible(host));
        assertNotEquals(linkage.fingerprint(), linkage.withAbiVersion(HostLinkage.ABI_VERSION + 1).fingerprint());
    }

    @Test
    void invocationScopedEffectAdapterStagesWithoutDirectMutation() {
        var host = ExpressionHost.create(HostAvailability.all());
        var linkage = HostLinkage.production(host);
        var inputs = new RecordingInputs();
        var provenance = new InvocationProvenance("player", "function/test", 1, 2, 3);
        var roaming = com.elfmcys.ysm.expression.TestRoamingStates.create(null, dev.semaweave.RoamingRole.PRODUCER);
        var context = MolangContext.logical(inputs, inputs, MolangContext.LogicalPhase.ACTION, linkage.access(inputs),
            roaming, provenance, () -> false, () -> true, 8);

        context.configureLimits(host.executionLimits());
        context.bindExpression("host-linkage", 0);
        context.beginTopLevel(true);
        try {
            TestHostInvocations.invoke(context, HostSymbol.YSM_PLAY_SOUND,
                List.of(soundId("sound"), RuntimeValues.ofString("name")));
            context.stage(new EffectCommand.Particle(false, "particle", new float[0]));
            context.stage(new EffectCommand.Controller(HostSymbol.CTRL_SET_ANIMATION, "idle", 0, 0, 0));
            TestHostInvocations.invoke(context, HostSymbol.YSM_DEFER, List.of(RuntimeValues.ofFloat(4.0f)));
            TestHostInvocations.invoke(context, HostSymbol.YSM_SYNC, List.of(RuntimeValues.ofFloat(1)));
            context.authorizeRoamingWrite(context.roamingState());
            context.roamingState().write(RoamingMember.EMOTION.memberName(), 2.0f);
        } finally {
            context.endTopLevel();
            context.releaseExpression();
        }
        assertEquals(5, inputs.prevalidated.size());
        assertEquals(0, inputs.externalMutations.get());

        var commits = new AtomicInteger();
        var committed = new ArrayList<EffectCommand>();
        context.commit(new OwnerEffectSink((commands, actualProvenance) -> {
            assertEquals(new InvocationProvenance("player", "host-linkage", 0, 2, 3), actualProvenance);
            committed.addAll(commands);
            return commits::incrementAndGet;
        }));
        assertEquals(1, commits.get());
        assertEquals(5, committed.size());
        assertEquals(inputs.prevalidated, committed);
        assertTrue(committed.get(0) instanceof EffectCommand.Sound);
        assertTrue(committed.get(1) instanceof EffectCommand.Particle);
        assertTrue(committed.get(2) instanceof EffectCommand.Controller);
        assertTrue(committed.get(3) instanceof EffectCommand.Deferred);
        assertTrue(committed.get(4) instanceof EffectCommand.Sync);
        assertEquals(2.0f, roaming.read(RoamingMember.EMOTION.memberName()));
        assertEquals(0, inputs.externalMutations.get());
    }

    private static RuntimeValue soundId(String value) {
        return RuntimeValues.ofString(value);
    }

    private static final class RecordingInputs implements InvocationInputs {
        private final List<EffectCommand> prevalidated = new ArrayList<>();
        private final AtomicInteger externalMutations = new AtomicInteger();

        @Override
        public void prevalidateEffect(EffectCommand command, MolangContext<?> scope) {
            prevalidated.add(command);
        }
    }
}
