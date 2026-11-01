package com.elfmcys.ysm.client.gui;

import com.elfmcys.ysm.geckolib3.geo.RenderContext;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** One GUI sample is applied during preparation and again during its draw borrow. */
public final class GuiFrameInput {
    private static final RenderContext CONTEXT = new RenderContext(false, false, false, true, false, false, false);
    private static final EquipmentSlot[] SLOTS = EquipmentSlot.values();
    private final LivingEntity subject;
    private final ItemStack[] equipment;
    private final float partialTick;
    private final boolean disableRotation;
    private final boolean hovered;
    private final boolean focused;
    private final long now;
    private final Float vehicleYaw;

    private GuiFrameInput(LivingEntity subject, float partialTick, boolean disableRotation, boolean disableEquipment,
        boolean hovered, boolean focused, long now) {
        this.subject = subject;
        this.partialTick = partialTick;
        this.disableRotation = disableRotation;
        this.hovered = hovered;
        this.focused = focused;
        this.now = now;
        vehicleYaw = subject.getVehicle() instanceof LivingEntity vehicle ? vehicle.getYRot() : null;
        equipment = subject instanceof Player ? new ItemStack[SLOTS.length] : null;
        if (equipment != null) {
            for (var slot : SLOTS)
                equipment[slot.ordinal()] = disableEquipment ? ItemStack.EMPTY : subject.getItemBySlot(slot).copy();
        }
    }

    public static GuiFrameInput capture(LivingEntity subject, float partialTick, boolean disableRotation,
        boolean disableEquipment, boolean hovered, boolean focused, long now) {
        return new GuiFrameInput(subject, partialTick, disableRotation, disableEquipment, hovered, focused, now);
    }

    public RenderContext context() { return CONTEXT; }
    public float partialTick() { return partialTick; }
    public boolean disableRotation() { return disableRotation; }
    public boolean hovered() { return hovered; }
    public boolean focused() { return focused; }
    public long now() { return now; }
    public float bodyYaw() { return disableRotation ? 180 : 200; }
    public float vehicleRotation() { return vehicleYaw == null ? 0 : vehicleYaw - bodyYaw(); }

    public Applied apply() {
        return new Applied();
    }

    public final class Applied implements AutoCloseable {
        private final float yBodyRot = subject.yBodyRot;
        private final float yBodyRotO = subject.yBodyRotO;
        private final float yRot = subject.getYRot();
        private final float yRotO = subject.yRotO;
        private final float xRot = subject.getXRot();
        private final float xRotO = subject.xRotO;
        private final float yHeadRot = subject.yHeadRot;
        private final float yHeadRotO = subject.yHeadRotO;
        private final ItemStack[] previous = equipment == null ? null : new ItemStack[equipment.length];
        private boolean closed;
        private int appliedEquipment;

        private Applied() {
            try {
                if (previous != null) {
                    for (var slot : SLOTS) {
                        var item = equipment[slot.ordinal()].copy();
                        previous[slot.ordinal()] = subject.getItemBySlot(slot);
                        appliedEquipment++;
                        setEquipment((Player)subject, slot, item);
                    }
                }
                subject.yBodyRot = subject.yBodyRotO = bodyYaw();
                subject.setYRot(bodyYaw());
                subject.yRotO = bodyYaw();
                subject.setXRot(0);
                subject.xRotO = 0;
                subject.yHeadRot = subject.yHeadRotO = vehicleYaw == null ? bodyYaw() : vehicleYaw;
            } catch (RuntimeException | Error failure) {
                try { close(); } catch (Throwable cleanup) {
                    var combined = GuiFrameTasks.append(failure, cleanup);
                    if (combined != failure && combined instanceof Error error)
                        throw error;
                }
                throw failure;
            }
        }

        @Override
        public void close() {
            if (closed)
                return;
            closed = true;
            subject.yBodyRot = yBodyRot;
            subject.yBodyRotO = yBodyRotO;
            subject.setYRot(yRot);
            subject.yRotO = yRotO;
            subject.setXRot(xRot);
            subject.xRotO = xRotO;
            subject.yHeadRot = yHeadRot;
            subject.yHeadRotO = yHeadRotO;
            Throwable failure = null;
            if (previous != null) {
                for (int index = 0; index < appliedEquipment; index++) {
                    var slot = SLOTS[index];
                    try { setEquipment((Player)subject, slot, previous[slot.ordinal()]); }
                    catch (Throwable error) { failure = GuiFrameTasks.append(failure, error); }
                }
            }
            if (failure instanceof Error error) throw error;
            if (failure instanceof RuntimeException error) throw error;
        }
    }

    private static void setEquipment(Player player, EquipmentSlot slot, ItemStack item) {
        var inventory = player.getInventory();
        if (slot == EquipmentSlot.MAINHAND)
            inventory.items.set(inventory.selected, item);
        else if (slot == EquipmentSlot.OFFHAND)
            inventory.offhand.set(0, item);
        else if (inventory.armor.size() > slot.getIndex())
            inventory.armor.set(slot.getIndex(), item);
    }
}
