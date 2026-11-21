package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.api.molang.DeclarationSchema;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.geckolib3.geo.RenderContext;
import com.elfmcys.ysm.geckolib3.model.provider.data.EntityModelData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.invoke.MethodType;
import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Representative provider boundaries use explicit fixtures; every declaration has separate scope coverage. */
class ProviderContractCoverageTest {
    @BeforeAll
    static void bootstrap() throws Exception {
        ComponentScopeTest.initializeMinecraftEntityClasses();
    }

    @Test
    void assignedExactMembersRunTheirSubjectAndArgumentFixtures() throws Exception {
        var assignments = new TreeMap<String, String[]>();
        try (var input = getClass().getResourceAsStream("/molang/provider-contract-coverage.tsv")) {
            assertNotNull(input);
            for (var line : new BufferedReader(new InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8))
                .lines().toList()) {
                if (line.startsWith("#") || line.isBlank())
                    continue;
                var fields = line.split("\t");
                assertEquals(4, fields.length);
                assertNull(assignments.put(fields[0], fields));
            }
        }
        var host = ExpressionHost.create(HostAvailability.all());
        var seen = new TreeSet<String>();
        var actions = new TreeSet<String>();
        var clientField = Minecraft.class.getDeclaredField("instance");
        clientField.setAccessible(true);
        var oldClient = clientField.get(null);
        var client = allocate(Minecraft.class);
        client.screen = allocate(TitleScreen.class);
        clientField.set(null, client);
        try {
            for (var declaration : host.declarations()) {
                for (var member : declaration.members()) {
                    var schema = member.schema();
                    var signature = MethodType.fromMethodDescriptorString(schema.descriptor(), declaration.loader());
                    var method = declaration.owner().getMethod(schema.javaName(), signature.parameterArray());
                    String exact = ProviderContractAudit.exact(method);
                    var assignment = assignments.get(exact);
                    if (assignment == null)
                        continue;
                    assertTrue(seen.add(exact), exact);
                    assertEquals(schema.subject(), assignment[2], exact);
                    if (schema.effect().equals("ACTION")) {
                        assertEquals("ACTION", assignment[1], exact);
                        assertEquals("boundary-inputs;domain-traces", assignment[3], exact);
                        actions.add(exact);
                    } else {
                        assertEquals("boundary-inputs", assignment[3], exact);
                    }
                    Entity subject = subject(member.subject());
                    var owner = TestComponentHolders.owner(subject);
                    TestComponentHolders.installProcessor(owner, RandomSource.create(41),
                        new com.elfmcys.ysm.client.sound.instance.SoundInstanceManager());
                    var provider = Modifier.isStatic(method.getModifiers()) ? null
                        : declaration.owner().getConstructor(AnimatableEntity.class).newInstance(owner);
                    var event = new AnimationEvent<>(owner, 1, 0.5f, 0.5f, RenderContext.levelImmutable(),
                        new EntityModelData(), null);
                    var context = context(subject, schema.effect().equals("ACTION"));
                    context.bindExpression("provider-audit", 0);
                    context.beginTopLevel(schema.effect().equals("ACTION"));
                    try {
                        if (provider instanceof EntityComponent<?> component)
                            component.beginEval(event);
                        for (int fixture = 0; fixture < 2; fixture++) {
                            var arguments = arguments(schema, context, fixture);
                            if (assignment[1].equals("MATH") && schema.fixed().size() > 1)
                                arguments[arguments.length - 1] = 1f;
                            ProviderContractAudit.invoke(method, provider, "INVOKE",
                                assignment[1] + ":" + (fixture == 0 ? "zero-empty-omitted" : "finite-supplied"),
                                arguments).verifyUnchanged();
                        }
                    } finally {
                        if (provider instanceof EntityComponent<?> component)
                            component.endEval();
                        context.endTopLevel();
                        context.releaseExpression();
                        context.closeRound();
                    }
                }
            }
        } finally {
            clientField.set(null, oldClient);
        }
        assertEquals(assignments.keySet(), seen, "stale or unaccepted fixture assignments");
        assertEquals(16, actions.size());
        System.out.println("PROVIDER_AUDIT queries=" + (seen.size() - actions.size()) + " actions=" + actions);
    }

    @Test
    void fieldIndexAndConversionRolesUseExactExecutableFixtures() throws Exception {
        var vector = new MolangVector(-0.0f, 2, 3);
        var numbers = it.unimi.dsi.fastutil.floats.FloatList.of(2, 3);
        var fixtures = new TreeMap<String, RoleFixture>();
        for (String axis : List.of("X", "Y", "Z"))
            addRole(fixtures, "FIELD_READ", HostBindings.class.getMethod("vector" + axis, MolangVector.class), null,
                new Object[] {vector});
        addRole(fixtures, "LENGTH", it.unimi.dsi.fastutil.floats.FloatList.class.getMethod("size"), numbers,
            new Object[0]);
        addRole(fixtures, "INDEX_READ", it.unimi.dsi.fastutil.floats.FloatList.class.getMethod("getFloat", int.class),
            numbers, new Object[] {1});
        addRole(fixtures, "CONVERSION", HostBindings.class.getMethod("booleanToFloat", boolean.class), null,
            new Object[] {false});
        addRole(fixtures, "CONVERSION", HostBindings.class.getMethod("floatToBoolean", float.class), null,
            new Object[] {-0.0f});
        var registry = ExpressionHost.create(HostAvailability.all()).registry();
        var accepted = new TreeSet<String>();
        for (var type : registry.types().values()) {
            if (type instanceof dev.semaweave.registry.HostStructTypeDescriptor record) {
                for (var field : record.members().values()) {
                    assertTrue(field.setter().isEmpty(), "new writer requires an audit fixture");
                    if (field.getter().isPresent())
                        accepted.add(roleKey("FIELD_READ", field.getter().orElseThrow()));
                }
            } else if (type instanceof dev.semaweave.registry.IndexedListTypeDescriptor list) {
                accepted.add(roleKey("LENGTH", list.length()));
                accepted.add(roleKey("INDEX_READ", list.indexedRead()));
            }
        }
        for (var conversion : registry.conversions())
            accepted.add(roleKey("CONVERSION", conversion.runtimeMethod()));
        assertEquals(fixtures.keySet(), accepted);
        for (var entry : fixtures.entrySet()) {
            var fixture = entry.getValue();
            ProviderContractAudit.invoke(fixture.method(), fixture.receiver(), fixture.role(),
                "legal-receiver-index-scalar", fixture.arguments()).verifyUnchanged();
        }
    }

    private record RoleFixture(String role, java.lang.reflect.Method method, Object receiver, Object[] arguments) {
    }

    private static void addRole(Map<String, RoleFixture> fixtures, String role, java.lang.reflect.Method method,
        Object receiver, Object[] arguments) {
        assertNull(fixtures.put(role + ":" + ProviderContractAudit.exact(method),
            new RoleFixture(role, method, receiver, arguments)));
    }

    private static String roleKey(String role, dev.semaweave.registry.JavaMemberBinding binding) throws Exception {
        return role + ":" + ProviderContractAudit
            .exact(binding.owner().getMethod(binding.methodName(), binding.methodType().parameterArray()));
    }

    private static Object[] arguments(DeclarationSchema.Member schema, MolangContext<?> context, int fixture)
        throws Exception {
        var arguments = new ArrayList<Object>();
        if (schema.injectContext())
            arguments.add(context);
        int supplied =
            fixture == 0 ? schema.minimum() : schema.tail() == null ? schema.maximum() : schema.minimum() + 1;
        if (!schema.shape().equals("FIXED"))
            arguments.add(supplied);
        for (int i = 0; i < schema.fixed().size(); i++) {
            int defaultIndex = i - (schema.fixed().size() - schema.defaults().size());
            arguments.add(i >= supplied && defaultIndex >= 0
                ? DeclarationSchema.literal(schema.fixed().get(i), schema.defaults().get(defaultIndex))
                : scalar(schema.fixed().get(i), fixture));
        }
        if (schema.tail() != null) {
            var type = LinkedDeclaration.carrier(schema.tail(), ProviderContractCoverageTest.class.getClassLoader());
            var tail = Array.newInstance(type, supplied - schema.fixed().size());
            for (int i = 0; i < Array.getLength(tail); i++)
                Array.set(tail, i, scalar(schema.tail(), fixture));
            arguments.add(tail);
        }
        return arguments.toArray();
    }

    private static Object scalar(DeclarationSchema.Value value, int fixture) {
        return switch (value.kind()) {
            case "FLOAT" -> fixture == 0 ? -0.0f : 1f;
            case "INT" -> fixture;
            case "BOOLEAN" -> fixture != 0;
            case "STRING" -> "";
            default -> throw new AssertionError(value);
        };
    }

    private static Entity subject(Class<?> required) throws Exception {
        if (required.isAssignableFrom(AuditEntity.class)) {
            var entity = allocate(AuditEntity.class);
            entity.world = allocate(AuditLevel.class);
            var position = Entity.class.getDeclaredField("position");
            position.setAccessible(true);
            position.set(entity, Vec3.ZERO);
            return entity;
        }
        if (required.isAssignableFrom(RemotePlayer.class)) {
            var player = allocate(RemotePlayer.class);
            var food = net.minecraft.world.entity.player.Player.class.getDeclaredField("foodData");
            food.setAccessible(true);
            food.set(player, new FoodData());
            return player;
        }
        if (required.isAssignableFrom(ArmorStand.class))
            return allocate(ArmorStand.class);
        throw new AssertionError("missing subject fixture " + required.getName());
    }

    private static MolangContext<Entity> context(Entity subject, boolean action) {
        var inputs = new InvocationInputs<Entity>() {
            public Entity entity() {
                return subject;
            }

        };
        var context = action
            ? MolangContext.logical(subject, inputs, MolangContext.LogicalPhase.ACTION,
                com.elfmcys.ysm.expression.TestRoamingStates.create(null, dev.semaweave.RoamingRole.PRODUCER),
                new InvocationProvenance("provider-audit", "boundary", 0, 1, 1))
            : MolangContext.observation(subject, inputs,
                com.elfmcys.ysm.expression.TestRoamingStates.create(null, dev.semaweave.RoamingRole.PRODUCER),
                new InvocationProvenance("provider-audit", "boundary", 0, 1, 1));
        context.deferredSubmit((payload, source) -> assertTrue(payload.size() > 0));
        return context;
    }

    private static <T> T allocate(Class<T> type) throws InstantiationException {
        return type.cast(com.elfmcys.ysm.util.UnsafeUtil.getUnsafe().allocateInstance(type));
    }

    private static final class AuditEntity extends Entity {
        private ClientLevel world;

        AuditEntity(ClientLevel world) {
            super(EntityType.ARMOR_STAND, null);
            this.world = world;
        }

        public ClientLevel level() {
            return world;
        }

        public BlockPos blockPosition() {
            return BlockPos.ZERO;
        }

        public Vec3 getDeltaMovement() {
            return Vec3.ZERO;
        }

        public boolean isSwimming() {
            return false;
        }

        public net.minecraft.world.entity.Pose getPose() {
            return net.minecraft.world.entity.Pose.STANDING;
        }

        protected void defineSynchedData() {}

        protected void readAdditionalSaveData(CompoundTag tag) {}

        protected void addAdditionalSaveData(CompoundTag tag) {}
    }

    private static final class AuditLevel extends ClientLevel {
        private AuditLevel() {
            super(null, null, null, null, 0, 0, null, null, false, 0);
        }

        public long getDayTime() {
            return 0;
        }

        public boolean isRaining() {
            return false;
        }

        public boolean isThundering() {
            return false;
        }

        public boolean canSeeSky(BlockPos position) {
            return true;
        }

        public BlockPos getHeightmapPos(Heightmap.Types type, BlockPos position) {
            return position;
        }

        public BlockState getBlockState(BlockPos position) {
            return Blocks.AIR.defaultBlockState();
        }
    }
}
