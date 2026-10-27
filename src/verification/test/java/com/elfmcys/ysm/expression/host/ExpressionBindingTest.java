package com.elfmcys.ysm.expression.host;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

final class ExpressionBindingTest {
    @Test
    void realClientPlayerClassesAndGuiPlayerShareTheDeclaredFamily() throws Exception {
        var family = net.minecraft.client.player.AbstractClientPlayer.class;
        var fakePlayer = Class.forName("com.elfmcys.ysm.client.gui.CustomGuiPlayerEntity$FakePlayer", false,
            ExpressionBindingTest.class.getClassLoader());
        assertTrue(family.isAssignableFrom(net.minecraft.client.player.LocalPlayer.class));
        assertTrue(family.isAssignableFrom(net.minecraft.client.player.RemotePlayer.class));
        assertTrue(family.isAssignableFrom(fakePlayer));
        assertTrue(java.lang.reflect.Modifier.isPublic(fakePlayer.getModifiers()));
    }

    @Test
    void oneFrozenPlanRetainsExactSubjectsAndMembersForEveryConsumer() throws Exception {
        var host = ExpressionHost.create(HostAvailability.all());
        var binding = host.expressionBinding();
        var plan = binding.bindingPlan();
        assertSame(binding, host.expressionBinding());
        assertEquals("subjectOrNull", plan.subjectAccessor().orElseThrow().methodName());
        assertEquals("validateInvocation", plan.invocationValidator().orElseThrow().methodName());
        for (var declaration : host.declarations())
            for (var member : declaration.members()) {
                var guard = plan.guard(member.binding()).orElseThrow();
                assertEquals(member.binding(), plan.resolve(member.binding()));
                assertEquals(member.subject(), guard.subject().orElseThrow());
                assertSame(member.subject(),
                    Class.forName(member.subject().getName(), false, host.registry().visibilityRoot()));
                assertEquals(member.schema().effect(), guard.effect().name());
            }
        assertFalse(plan.conditionallyAbsentProviderIds(host.registry()).isEmpty());
        assertEquals(host.declarations().stream().flatMap(declaration -> declaration.members().stream())
            .map(LinkedDeclaration.Member::subject).distinct().count(), plan.subjectTypes().size());
    }

    @Test
    void publishedDeclarationEnvironmentChangesTheStaticResourceIdentity() {
        var missing = ExpressionHost.create(HostAvailability.none());
        var available = ExpressionHost.create(HostAvailability.all());
        assertNotEquals(missing.expressionBinding().fingerprint(), available.expressionBinding().fingerprint());
        var repeated = ExpressionHost.create(HostAvailability.none());
        assertEquals(missing.expressionBinding().fingerprint(), repeated.expressionBinding().fingerprint());
        assertEquals(missing.expressionBinding(), repeated.expressionBinding());
    }

    @Test
    void rootSubjectConstraintsCoexistWithoutChangingTheDeclaredLanguage() {
        var host = ExpressionHost.create(HostAvailability.none());
        var living = host.expressionBinding(net.minecraft.world.entity.LivingEntity.class);
        var player = host.expressionBinding(net.minecraft.client.player.AbstractClientPlayer.class);
        var gui = host.expressionBinding(com.elfmcys.ysm.client.gui.CustomGuiPlayerEntity.FakePlayer.class);
        assertEquals(3, java.util.Set.of(living.fingerprint(), player.fingerprint(), gui.fingerprint()).size());
        assertEquals(host.expressionBinding().environment(), player.environment());
        assertEquals(host.expressionBinding().bindingPlan().subjectTypes(), gui.bindingPlan().subjectTypes());
        assertEquals(net.minecraft.world.entity.LivingEntity.class,
            living.bindingPlan().rootSubjectType().orElseThrow());
        assertEquals(player, host.expressionBinding(net.minecraft.client.player.AbstractClientPlayer.class));
    }

    @Test
    void sameBinaryNameDoesNotMakeDifferentRootClassesInterchangeable() throws Exception {
        var type = RootSubject.class;
        byte[] bytes;
        try (var input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            bytes = java.util.Objects.requireNonNull(input).readAllBytes();
        }
        var isolated = new ClassLoader(type.getClassLoader()) {
            Class<?> defineSubject() {
                return defineClass(type.getName(), bytes, 0, bytes.length);
            }
        }.defineSubject();
        var host = ExpressionHost.create(HostAvailability.none());
        var first = host.expressionBinding(type);
        var second = host.expressionBinding(isolated);
        assertEquals(first.fingerprint(), second.fingerprint());
        assertNotEquals(first, second);
        assertEquals(2, java.util.Set.of(first, second).size());
    }

    @Test
    void noThirdPartyDeclarationsReuseTheBuiltinView() {
        var host = ExpressionHost.create(HostAvailability.all());
        assertSame(host, host.builtinOnly());
    }

    public static final class RootSubject {
    }
}
