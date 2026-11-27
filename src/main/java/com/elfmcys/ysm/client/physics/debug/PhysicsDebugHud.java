package com.elfmcys.ysm.client.physics.debug;

import com.elfmcys.ysm.client.physics.debug.PhysicsDebugController.View;
import com.elfmcys.ysm.model.resource.client.PhysicsAssetMetadata;
import com.elfmcys.ysm.natives.physics.PhysicsDebugSnapshot.Contact;
import com.elfmcys.ysm.natives.physics.PhysicsDebugSnapshot.Joint;
import com.elfmcys.ysm.natives.physics.PhysicsDebugSnapshot.Reading;
import com.elfmcys.ysm.natives.physics.PhysicsDebugSnapshot.Vec3;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AxisConfigProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.ConstraintFrameProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.LimitKind;
import com.elfmcys.ysm.proto.mixel.asset.model.data.PhysicsConstraintKind;
import com.elfmcys.ysm.proto.mixel.asset.model.data.PhysicsConstraintProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.PhysicsNodeKind;
import com.elfmcys.ysm.proto.mixel.asset.model.data.PhysicsNodeProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.SpringParametersProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.SpringTarget;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

public final class PhysicsDebugHud {
    private static final int MAX_PANEL_WIDTH = 432;
    private static final int MARGIN = 6;
    private static final String SHORT_DASH = "—";

    private PhysicsDebugHud() {}

    public static void render(GuiGraphics graphics, int screenWidth, int screenHeight, View view) {
        var font = Minecraft.getInstance().font;
        var page = layout(font, screenWidth, screenHeight, lines(view), view.page());
        if (page == null) {
            return;
        }
        PhysicsDebugController.publishPageCount(page.count());
        int lineHeight = font.lineHeight + 1;
        int visibleRows = page.rows().size() + (page.footer() == null ? 0 : 1);
        int panelHeight = 2 * MARGIN + (int)Math.ceil(visibleRows * lineHeight * page.scale());
        graphics.fill(MARGIN, MARGIN, MARGIN + page.width(), MARGIN + panelHeight, 0xE51B212B);
        var pose = graphics.pose();
        pose.pushPose();
        try {
            pose.translate(2 * MARGIN, 2 * MARGIN, 0);
            pose.scale(page.scale(), page.scale(), 1);
            for (int i = 0; i < page.rows().size(); i++) {
                graphics.drawString(font, page.rows().get(i), 0, i * lineHeight, 0xFFE8ECF3, false);
            }
            if (page.footer() != null) {
                pose.translate(0, page.rows().size() * lineHeight, 0);
                float fit = Math.min(1F, (page.width() - 2 * MARGIN) / (page.scale() * font.width(page.footer())));
                pose.scale(fit, fit, 1);
                graphics.drawString(font, page.footer(), 0, 0, 0xFFB9C5D6, false);
            }
        } finally {
            pose.popPose();
        }
    }

    static @Nullable Page layout(Font font, int screenWidth, int screenHeight, List<Component> content,
        int requestedPage) {
        int width =
            Math.min(Math.min(MAX_PANEL_WIDTH, screenWidth - 2 * MARGIN), Math.max(112, screenWidth / 2 - 2 * MARGIN));
        int height = screenHeight - 2 * MARGIN;
        if (width <= 2 * MARGIN || height <= 2 * MARGIN) {
            return null;
        }
        int lineHeight = font.lineHeight + 1;
        var rows = new ArrayList<FormattedCharSequence>();
        float scale = 1F;
        for (var line : content) {
            rows.addAll(font.split(line, width - 2 * MARGIN));
        }
        if (rows.size() * lineHeight + 2 * MARGIN > height) {
            scale = 0.75F;
            rows.clear();
            for (var line : content) {
                rows.addAll(font.split(line, (int)((width - 2 * MARGIN) / scale)));
            }
        }
        int maxRows = (int)((height - 2 * MARGIN) / (lineHeight * scale));
        if (rows.size() <= maxRows) {
            return new Page(width, scale, rows, 1, null);
        }
        int contentRows = Math.max(1, maxRows - 1);
        int count = (rows.size() + contentRows - 1) / contentRows;
        int page = Mth.clamp(requestedPage, 1, count);
        int first = (page - 1) * contentRows;
        var footer = text("page", page, count, page < count ? "next" : "prev");
        return new Page(width, scale, rows.subList(first, Math.min(first + contentRows, rows.size())), count, footer);
    }

    record Page(int width, float scale, List<FormattedCharSequence> rows, int count, @Nullable Component footer) {
    }

    static List<Component> lines(View view) {
        var lines = new ArrayList<Component>();
        lines.add(text("title", view.targetName()).withStyle(ChatFormatting.AQUA));
        if (view.failure() != null) {
            lines.add(text("failure", text("contact.no_sample"), view.failure()).withStyle(ChatFormatting.RED));
        } else if (view.snapshot() == null) {
            lines.add(text("contact.no_sample").withStyle(ChatFormatting.YELLOW));
        }
        var selection = view.selection();
        switch (selection.kind()) {
            case ALL -> {
                lines.add(text("overview", view.metadata().nodes().size(), view.metadata().constraints().size()));
                lines.add(text("select"));
            }
            case NODE -> node(lines, view, selection.first());
            case PAIR -> pair(lines, view, selection.first(), selection.second());
            case CONSTRAINT -> constraint(lines, view, selection.first());
        }
        return lines;
    }

    private static void node(List<Component> lines, View view, int index) {
        var node = view.metadata().nodes().get(index);
        var sample = view.snapshot() == null ? null : view.snapshot().nodes().get(index);
        var type = text("node." + key(node.kind()));
        String bone = node.boneUnsafe();
        lines.add(text("node", node.id(), type, bone.isEmpty() ? text("unnamed_bone") : bone)
            .withStyle(ChatFormatting.YELLOW));
        if (node.hasPresetTag()) {
            lines.add(text("preset", node.presetTagUnsafe()));
        }
        if (node.kind() != PhysicsNodeKind.ANCHOR) {
            lines.add(text("material", number(node.friction().orElse(0.5F)), number(node.bounciness().orElse(0F))));
            lines.add(text("collections", collections(view.metadata(), mask(node))));
        }
        if (node.kind() == PhysicsNodeKind.DYNAMIC) {
            lines.add(text("mass", number(node.massUnsafe()) + " kg", number(node.gravityScale().orElse(1F))));
            lines.add(text("damping", number(node.linearDamping().orElse(0.04F)),
                number(node.angularDamping().orElse(0.1F))));
        } else if (node.kind() == PhysicsNodeKind.PROXY) {
            lines.add(text("kinematic"));
        }
        if (sample != null) {
            if (sample.active()) {
                lines.add(text("active", number(sample.scale())).withStyle(ChatFormatting.GREEN));
            } else {
                var culprit = sample.culpritName();
                lines.add(text("inactive." + key(sample.deactivation()),
                    culprit == null ? SHORT_DASH : culprit.isEmpty() ? text("unnamed_bone") : culprit)
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
            }
        }
    }

    private static void pair(List<Component> lines, View view, int first, int second) {
        var nodes = view.metadata().nodes();
        var a = nodes.get(first);
        var b = nodes.get(second);
        int intersection = mask(a) & mask(b);
        var direct = Component.empty();
        boolean denied = false;
        for (var constraint : view.metadata().constraints()) {
            if (constraint.nodeA().equals(a.id()) && constraint.nodeB().equals(b.id())
                || constraint.nodeA().equals(b.id()) && constraint.nodeB().equals(a.id())) {
                boolean disables = constraint.disableCollisions().orElse(true);
                if (!direct.getSiblings().isEmpty()) {
                    direct.append(", ");
                }
                direct.append(constraint.id()).append(" ").append(text(disables ? "on" : "off"));
                denied |= disables;
            }
        }
        boolean allowed = first != second && intersection != 0 && !denied
            && (a.kind() == PhysicsNodeKind.DYNAMIC || b.kind() == PhysicsNodeKind.DYNAMIC);
        lines.add(text("pair", a.id(), b.id(), text(allowed ? "pair.allowed" : "pair.blocked"))
            .withStyle(allowed ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
        lines.add(text("pair.intersection", collections(view.metadata(), intersection)));
        lines.add(text("pair.disabled_by", direct.getSiblings().isEmpty() ? SHORT_DASH : direct));
        Contact contact = view.snapshot() == null ? Contact.NO_SAMPLE : view.snapshot().contact(first, second);
        lines.add(text("contact", text("contact." + key(contact))).withStyle(ChatFormatting.AQUA));
        node(lines, view, first);
        if (second != first) {
            node(lines, view, second);
        }
    }

    private static int mask(PhysicsNodeProto node) {
        return node.kind() == PhysicsNodeKind.ANCHOR ? 0 : node.collectionMask().orElse(1);
    }

    private static Object collections(PhysicsAssetMetadata metadata, int mask) {
        if (mask == 0) {
            return text("collections.empty");
        }
        var result = new StringJoiner(", ");
        for (int slot = 1; slot <= 20; slot++) {
            if ((mask & (1 << (slot - 1))) != 0) {
                String label = Integer.toString(slot);
                for (var name : metadata.collections()) {
                    if (name.slot() == slot) {
                        label += " " + name.name();
                        break;
                    }
                }
                result.add(label);
            }
        }
        return result.toString();
    }

    private static void constraint(List<Component> lines, View view, int index) {
        var definition = view.metadata().constraints().get(index);
        var sample = view.snapshot() == null ? null : view.snapshot().joints().get(index);
        lines.add(
            text("joint", definition.id(), text("joint." + key(definition.kind()))).withStyle(ChatFormatting.YELLOW));
        if (definition.hasPresetTag()) {
            lines.add(text("preset", definition.presetTagUnsafe()));
        }
        lines.add(text("endpoints", definition.nodeA(), definition.nodeB()));
        lines.add(text("basis", "A", basis(definition.frameA())));
        lines.add(text("basis", "B", basis(definition.frameB())));
        if (sample != null && sample.scaleA() <= 0) {
            lines.add(text("invalid_scale_a", definition.nodeA()).withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        boolean hinge = definition.kind() == PhysicsConstraintKind.HINGE;
        lines.add(text("current",
            reading(sample == null ? null : sample.current(), sample == null ? 0 : sample.scaleA(), hinge)));
        lines.add(
            text("rest", reading(sample == null ? null : sample.rest(), 1F, hinge)).withStyle(ChatFormatting.GOLD));
        lines.add(text("animation",
            reading(sample == null ? null : sample.animation(), sample == null ? 0 : sample.scaleA(), hinge))
            .withStyle(ChatFormatting.AQUA));
        axisSettings(lines, definition);
    }

    private static Object reading(@Nullable Reading reading, float scale, boolean hinge) {
        if (reading == null) {
            return text("unavailable");
        }
        Object linear = scale > 0 ? vector(reading.linear(), 16D / scale) + " px" : text("unavailable");
        Object angular = reading.rotationSingular() ? text("singular") : hinge
            ? number(Math.toDegrees(reading.angular().z())) + "° (Z)" : vector(reading.angular(), 180D / Math.PI) + "°";
        return text("reading", linear, angular);
    }

    private static String vector(Vec3 v, double factor) {
        return "(" + number(v.x() * factor) + ", " + number(v.y() * factor) + ", " + number(v.z() * factor) + ")";
    }

    private static String basis(ConstraintFrameProto frame) {
        return "X " + vector(frame.axisX()) + "  Y " + vector(frame.axisY()) + "  Z " + vector(frame.axisZ());
    }

    private static String vector(List<Float> xyz) {
        return "(" + number(xyz.get(0)) + ", " + number(xyz.get(1)) + ", " + number(xyz.get(2)) + ")";
    }

    private static void axisSettings(List<Component> lines, PhysicsConstraintProto joint) {
        switch (joint.kind()) {
            case FIXED -> lines.add(text("fixed_axes"));
            case HINGE -> {
                lines.add(text("hinge_axes"));
                axis(lines, "Z", true, joint.angularZUnsafe());
            }
            case POINT, GENERIC -> {
                if (joint.kind() == PhysicsConstraintKind.POINT) {
                    lines.add(text("locked_linear"));
                } else {
                    axis(lines, "X", false, joint.linearXUnsafe());
                    axis(lines, "Y", false, joint.linearYUnsafe());
                    axis(lines, "Z", false, joint.linearZUnsafe());
                }
                axis(lines, "X", true, joint.angularXUnsafe());
                axis(lines, "Y", true, joint.angularYUnsafe());
                axis(lines, "Z", true, joint.angularZUnsafe());
                if (joint.hasCone()) {
                    var cone = joint.coneUnsafe();
                    var buffer =
                        cone.limit() == LimitKind.SOFT ? parameters(cone.softLimitSpringUnsafe(), true) : text("off");
                    lines.add(text("cone", number(Math.toDegrees(cone.halfAngleUnsafe())),
                        text("limit." + key(cone.limit())), buffer));
                }
            }
            default -> throw new IllegalStateException("Unvalidated physics constraint");
        }
    }

    private static void axis(List<Component> lines, String name, boolean angular, @Nullable AxisConfigProto axis) {
        var limit = axis == null ? LimitKind.FREE : axis.limit().orElse(LimitKind.FREE);
        var row = text(angular ? "angular_axis" : "linear_axis", name, text("limit." + key(limit)));
        if (limit == LimitKind.HARD || limit == LimitKind.SOFT) {
            double factor = angular ? 180D / Math.PI : 1F;
            row.append(" [" + number(axis.minUnsafe() * factor) + ", " + number(axis.maxUnsafe() * factor) + "]"
                + (angular ? "°" : " px"));
        }
        var spring = axis == null ? null : axis.springUnsafe();
        var target = spring == null ? SpringTarget.OFF : spring.target();
        lines.add(row.append("  ·  ")
            .append(text("center", text("target." + key(target)),
                target == SpringTarget.OFF ? "" : parameters(spring.parametersUnsafe(), angular)))
            .append("  ·  ").append(text("buffer",
                limit == LimitKind.SOFT ? parameters(axis.softLimitSpringUnsafe(), angular) : text("off"))));
    }

    private static Component parameters(SpringParametersProto parameters, boolean angular) {
        return switch (parameters.modeCase()) {
            case FREQUENCY_DAMPING -> {
                var values = parameters.frequencyDamping();
                yield text("frequency_damping", number(values.frequency()), number(values.dampingRatioUnsafe()));
            }
            case STIFFNESS_DAMPING -> {
                var values = parameters.stiffnessDamping();
                yield text("stiffness_damping", number(values.stiffnessUnsafe()), angular ? "N·m/rad" : "N/m",
                    number(values.dampingUnsafe()), angular ? "N·m·s/rad" : "N·s/m");
            }
            default -> throw new IllegalStateException("Unvalidated spring parameters");
        };
    }

    private static MutableComponent text(String key, Object... arguments) {
        return Component.translatable("hud.ysm.physics." + key, arguments);
    }

    private static String key(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.4g", value == 0 ? 0D : value);
    }
}
