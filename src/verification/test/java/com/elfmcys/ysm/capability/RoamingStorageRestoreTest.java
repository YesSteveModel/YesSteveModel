package com.elfmcys.ysm.capability;

import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.expression.aot.EntityExpressionRuntime;
import com.elfmcys.ysm.expression.aot.RenderTargetExpressionModule;
import com.elfmcys.ysm.expression.aot.TestExpressionModules;
import com.elfmcys.ysm.expression.host.TestComponentHolders;
import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.network.protocol.PlayerStateReportPlanner;
import com.elfmcys.ysm.network.protocol.PlayerStateReportPolicy;
import com.elfmcys.ysm.network.protocol.PlayerStateValidator;
import com.elfmcys.ysm.util.StringPool;
import dev.semaweave.RoamingRole;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class RoamingStorageRestoreTest {
    @Test
    void serverPersistsSelectionButNoRoamingHistoryAndRolesRemainDistinct() {
        var model = ModelId.parse("fixture/model");
        var state = new ModelInfoCapability();
        state.setModelAndTexture(model, "skin");
        state.commitSelection(new CommittedSelection(com.elfmcys.ysm.model.domain.PublishedModelId.model(model), 10,
            new Object(), new com.elfmcys.ysm.network.protocol.RoamingDictionary(new String[] {"a"}),
            new com.elfmcys.ysm.network.protocol.RoamingValues(new short[] {0}, new float[] {5}), "skin", ""));
        var tag = state.serializeNBT();
        assertFalse(tag.contains("molang_storage"));
        assertFalse(tag.contains("roaming"));
        assertFalse(tag.contains("star_models"));
        var restored = new ModelInfoCapability();
        restored.deserializeNBT(tag);
        assertEquals(com.elfmcys.ysm.model.domain.PublishedModelId.model(model), restored.getModelId());
        assertEquals("skin", restored.getSelectTexture());
        assertTrue(restored.getMolangVars().orElseThrow().isEmpty());
        state.setModelSelection(com.elfmcys.ysm.model.domain.PublishedModelId.INTRINSIC_DEFAULT, "");
        assertTrue(state.getMolangVars().orElseThrow().isEmpty());
        state.setModelAndTexture(model, "skin");
        assertTrue(state.getMolangVars().orElseThrow().isEmpty());
    }
}
