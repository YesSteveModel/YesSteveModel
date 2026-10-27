package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.api.annotation.MolangNullable;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.floats.FloatList;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class ProviderContractAuditTest {
    public record Point(float x, String name) {
    }

    public static final class Provider {
        public static float scalar() {
            return 1;
        }

        public static Float boxed() {
            return null;
        }

        public static Integer integer() {
            return null;
        }

        public static Boolean bool() {
            return null;
        }

        public static String text() {
            return null;
        }

        @MolangNullable
        public static Point nullable() {
            return null;
        }

        public static Point point() {
            return new Point(2, "point");
        }

        public static FloatList numbers() {
            return new FloatArrayList(new float[] {2, 3});
        }

        public static List<String> strings() {
            return new ArrayList<>(List.of("a", "b"));
        }

        public static List<Point> points() {
            return new ArrayList<>(List.of(point()));
        }
    }

    @Test
    void nonFiniteFunctionScalarIsMissingWhileCompositeAuditStaysSeparate() throws Exception {
        for (var invalid : new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            ProviderContractAudit.publish(Provider.class.getMethod("scalar"), invalid).verifyUnchanged();
            ProviderContractAudit.publish(Provider.class.getMethod("boxed"), invalid).verifyUnchanged();
            reject("numbers", new FloatArrayList(new float[] {2, invalid}), "$[1]: non-finite value");
            reject("point", new Point(invalid, "point"), "$.x: non-finite value");
        }
    }

    @Test
    void nullInForbiddenContainerElementAndFieldIsLocated() throws Exception {
        reject("point", null, "$: forbidden null");
        reject("numbers", null, "$: forbidden null");
        reject("strings", Arrays.asList("ok", null), "$[1]: forbidden null");
        reject("points", Arrays.asList(Provider.point(), null), "$[1]: forbidden null");
        reject("point", new Point(2, null), "$.name: forbidden null");
        reject("points", List.of(new Point(2, null)), "$[0].name: forbidden null");
    }

    @Test
    void backingMutationIsDetectedWithoutChangingThePublishedValue() throws Exception {
        var numbers = Provider.numbers();
        var numericPublication = ProviderContractAudit.publish(Provider.class.getMethod("numbers"), numbers);
        var strings = Provider.strings();
        var stringPublication = ProviderContractAudit.publish(Provider.class.getMethod("strings"), strings);
        var points = Provider.points();
        var recordPublication = ProviderContractAudit.publish(Provider.class.getMethod("points"), points);
        numericPublication.verifyUnchanged();
        stringPublication.verifyUnchanged();
        recordPublication.verifyUnchanged();
        numbers.set(0, 9);
        strings.set(1, "changed");
        points.set(0, new Point(4, "replacement"));
        for (var publication : List.of(numericPublication, stringPublication, recordPublication)) {
            var failure = assertThrows(IllegalStateException.class, publication::verifyUnchanged);
            assertTrue(failure.getMessage().contains(publication.label() + " $: modified after publication"));
            System.out.println("DEVELOPMENT_VIOLATION," + failure.getMessage());
        }
        assertEquals(9, numbers.getFloat(0));
    }

    @Test
    void legalNullableResultsAndPresentZerosProduceNoViolation() throws Exception {
        for (var name : List.of("boxed", "integer", "bool", "text", "nullable")) {
            ProviderContractAudit.publish(Provider.class.getMethod(name), null).verifyUnchanged();
        }
        for (var zero : new float[] {0, -0.0f}) {
            ProviderContractAudit.publish(Provider.class.getMethod("scalar"), zero).verifyUnchanged();
        }
        ProviderContractAudit.publish(Provider.class.getMethod("bool"), false).verifyUnchanged();
        ProviderContractAudit.publish(Provider.class.getMethod("point"), new Point(0, "")).verifyUnchanged();
        ProviderContractAudit.publish(Provider.class.getMethod("numbers"), FloatList.of()).verifyUnchanged();
    }

    @Test
    void expiredAndCrossThreadBorrowsAreLocatedAgainstTheRealContext() throws Exception {
        var invocation = invocation();
        var context = invocation;
        context.bindExpression("audit", 0);
        context.beginTopLevel(false);
        var borrow = ProviderContractAudit.borrow("Provider#read owner=target generation=1", context);
        borrow.verify();
        var worker = Executors.newSingleThreadExecutor();
        try {
            var message =
                worker.submit(() -> assertThrows(IllegalStateException.class, borrow::verify).getMessage()).get();
            assertTrue(message.contains("context: cross-thread borrow"));
            System.out.println("DEVELOPMENT_VIOLATION," + message);
        } finally {
            worker.shutdownNow();
            context.endTopLevel();
            context.releaseExpression();
            context.closeRound();
        }
        var expired = assertThrows(IllegalStateException.class, borrow::verify);
        assertTrue(expired.getMessage().contains("context: expired borrow"));
        System.out.println("DEVELOPMENT_VIOLATION," + expired.getMessage());
    }

    @Test
    void forbiddenEntryRejectsDirectJavaActionBeforeRecordingAnEffect() {
        var context = invocation();
        context.bindExpression("audit", 0);
        context.beginTopLevel(false);
        try {
            var action = assertThrows(IllegalStateException.class, () -> context.playSound("voice", "voice", 0, 1, 1));
            assertTrue(action.getMessage().contains("entry forbids ACTION"));
        } finally {
            context.endTopLevel();
            context.releaseExpression();
            context.closeRound();
        }
    }

    private static MolangContext<Entity> invocation() {
        return MolangContext.logical(new EntityView() {}, new RenderView() {}, MolangContext.LogicalPhase.ACTION,
            new ControlledHostFixture(),
            com.elfmcys.ysm.expression.TestRoamingStates.create(null, dev.semaweave.RoamingRole.PRODUCER),
            new InvocationProvenance("target", "development", 1, 2, 3), () -> false, () -> true, 8);
    }

    private static void reject(String method, Object value, String expected) throws Exception {
        var declaration = Provider.class.getMethod(method);
        var failure =
            assertThrows(IllegalStateException.class, () -> ProviderContractAudit.publish(declaration, value));
        assertTrue(failure.getMessage().contains("#" + method + " " + expected), failure.getMessage());
        System.out.println("DEVELOPMENT_VIOLATION," + failure.getMessage());
    }
}
