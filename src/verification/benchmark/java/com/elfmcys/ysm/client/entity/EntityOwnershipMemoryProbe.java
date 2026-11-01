package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.geckolib3.core.builder.Animation;
import com.elfmcys.ysm.geckolib3.core.builder.LoopType;
import com.elfmcys.ysm.model.resource.client.AnimationStore;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Measures reachable cache storage; shared resource graphs are outside the owned-byte total. */
public final class EntityOwnershipMemoryProbe {
    private static Object field(Object value, String name) throws Exception {
        var field = value.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(value);
    }

    public static void main(String[] args) throws Exception {
        int owners = Integer.parseInt(args[0]);
        boolean positive = args[1].equals("positive");
        var state = new EntityOwnershipBenchmark.Workload();
        state.owners = owners;
        state.query = "dynamic";
        try {
            state.setup();
            try (var source = positive ? AnimationStore.eager(animations(state.missing)) : AnimationStore.eager(Map.of())) {
                for (var owner : state.instances) {
                    owner.readBatchStore = source;
                    var cache = field(owner.controller, "animationCache");
                    ((Map<?, ?>)field(cache, "entries")).clear();
                }
                state.rounds = 0;
                for (int count : new int[] {0, 100, 1000, 10000}) {
                    while (state.rounds < count) state.frame();
                    var seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
                    long bytes = 0;
                    int entries = 0;
                    for (var owner : state.instances) {
                        var cache = field(owner.controller, "animationCache");
                        var map = (Map<?, ?>)field(cache, "entries");
                        bytes += size(cache, seen) + size(map, seen);
                        var tableField = java.util.HashMap.class.getDeclaredField("table");
                        tableField.setAccessible(true);
                        bytes += size(tableField.get(map), seen);
                        for (var entry : map.entrySet()) {
                            bytes += size(entry, seen) + size(entry.getKey(), seen) + size(entry.getValue(), seen);
                            bytes += size(field(entry.getKey(), "value"), seen);
                        }
                        entries += map.size();
                    }
                    System.out.println("MEMORY owners=" + owners + " kind=" + args[1] + " names=" + count
                        + " entries=" + entries + " ownedReachableBytes=" + bytes);
                }
                for (var owner : state.instances) {
                    owner.retire();
                    var cache = field(owner.controller, "animationCache");
                    if (field(cache, "source") != null || !((Map<?, ?>)field(cache, "entries")).isEmpty())
                        throw new AssertionError("Retired cache retained resource references");
                }
                System.out.println("RELEASE owners=" + owners + " kind=" + args[1] + " entries=0 source=null");
            }
        } finally { state.close(); }
    }

    private static long size(Object value, java.util.Set<Object> seen) {
        return value == null || !seen.add(value) ? 0 : EntityOwnershipProbeAgent.instrumentation.getObjectSize(value);
    }

    private static Map<String, Animation> animations(String[] names) {
        var result = new java.util.HashMap<String, Animation>();
        for (var name : names) result.put(name, new Animation(name, 400, LoopType.LOOP, null,
            List.of(), List.of(), List.of()));
        return result;
    }
}
