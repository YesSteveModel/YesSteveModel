package com.elfmcys.ysm.tool;

import com.elfmcys.ysm.model.catalog.builtin.BuiltinModelIndex;
import com.elfmcys.ysm.model.catalog.content.DefaultAnimationKey;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.expression.inventory.ModelProgramInventory;
import com.elfmcys.ysm.expression.inventory.ProgramRole;
import dev.semaweave.ProgramId;
import dev.semaweave.ProgramKey;
import dev.semaweave.compilation.ProgramReplacement;
import dev.semaweave.compilation.ReplacementResult;
import dev.semaweave.compilation.SemanticFailureReason;
import dev.semaweave.program.ReadOnlyBytes;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinModelIndexToolTest {
    private static final ModelPath DEFAULT_PATH = new ModelPath("default");

    @Test
    void defaultContractContainsOnlyTheDefaultModelAndCurrentAnimationHashes() throws Exception {
        var key = new DefaultAnimationKey("player/main", "idle");
        var current = hash(2);

        var contract = BuiltinModelIndexTool.createDefaultContract(hash(1), Map.of(key, current));

        assertEquals(List.of(DEFAULT_PATH), contract.entries().stream().map(BuiltinModelIndex.Entry::path).toList());
        assertEquals(List.of(current), contract.animationEntries().get(0).acceptedPayloadHashes());
    }

    @Test
    void indexGenerationMergesHistoryWithoutWeakeningTheDefaultProof() throws Exception {
        var key = new DefaultAnimationKey("player/main", "idle");
        var defaultHash = hash(1);
        var current = hash(2);
        var historical = hash(3);
        var contract = BuiltinModelIndexTool.createDefaultContract(defaultHash, Map.of(key, current));

        var index =
            BuiltinModelIndexTool.createIndex(Map.of(DEFAULT_PATH, defaultHash, new ModelPath("misc/example"), hash(4)),
                contract, Map.of(key, Set.of(historical)));

        assertTrue(index.accepts(key, current));
        assertTrue(index.accepts(key, historical));
        assertDoesNotThrow(() -> BuiltinModelIndexTool.validateDefaultContract(index, contract));
        assertThrows(IOException.class,
            () -> BuiltinModelIndexTool.createIndex(Map.of(DEFAULT_PATH, hash(5)), contract, Map.of()));
    }

    @Test
    void rejectsAContractWithExtraModelsOrHistoricalAnimationHashes() throws Exception {
        var key = new DefaultAnimationKey("player/main", "idle");
        var defaultHash = hash(1);
        var current = hash(2);
        var historical = hash(3);
        var modelHashes = Map.of(DEFAULT_PATH, defaultHash);
        var extraModelContract =
            BuiltinModelIndex.of(Map.of(DEFAULT_PATH, defaultHash, new ModelPath("misc/example"), hash(4)));
        var historicalContract =
            BuiltinModelIndex.of(modelHashes, Map.of(key, current), Map.of(key, Set.of(historical)));

        assertThrows(IOException.class,
            () -> BuiltinModelIndexTool.createIndex(modelHashes, extraModelContract, Map.of()));
        assertThrows(IOException.class,
            () -> BuiltinModelIndexTool.createIndex(modelHashes, historicalContract, Map.of()));
    }

    @Test
    void writesDeterministicVerificationReceipt() {
        assertEquals("""
            {
              "formatVersion": 3,
              "builtinIndexHash": "%s",
              "verifiedModelCount": 27
            }
            """.formatted(hash(9)), BuiltinModelIndexTool.verificationReceipt(hash(9), 27));
        assertThrows(IllegalArgumentException.class, () -> BuiltinModelIndexTool.verificationReceipt(hash(9), 0));
    }

    @Test
    void builtinAdmissionConsumesExplicitReplacementFactsAtCurrentLocations() {
        var bindingKey = new ProgramKey(ReadOnlyBytes.copyOf(new byte[] {1}));
        var storageKey = new ProgramKey(ReadOnlyBytes.copyOf(new byte[] {2}));
        var arityKey = new ProgramKey(ReadOnlyBytes.copyOf(new byte[] {3}));
        var replacements = List.of(
            new ProgramReplacement(new ProgramId(0), bindingKey, SemanticFailureReason.MISSING_SYMBOL, "root/0",
                "missing binding", ReplacementResult.BOOL_FALSE),
            new ProgramReplacement(new ProgramId(1), storageKey, SemanticFailureReason.TYPE_MISMATCH, "root/1",
                "incompatible assignment", ReplacementResult.F32_ZERO),
            new ProgramReplacement(new ProgramId(2), arityKey, SemanticFailureReason.ARITY_MISMATCH, "root/2",
                "invalid argument count", ReplacementResult.VOID_NOOP));

        assertEquals(
            List.of(
                "player/controller/main/idle/transition " + "[CONTROLLER_TRANSITION]: reason=MISSING_SYMBOL "
                    + "result=BOOL_FALSE node=root/0 message=missing binding",
                "player/animation/main/idle/blend-weight " + "[ANIMATION_SAMPLE]: reason=TYPE_MISMATCH "
                    + "result=F32_ZERO node=root/1 " + "message=incompatible assignment",
                "player/animation/main/idle/instruction " + "[ANIMATION_INSTRUCTION:0]: reason=ARITY_MISMATCH "
                    + "result=VOID_NOOP node=root/2 " + "message=invalid argument count"),
            BuiltinModelIndexTool.replacementDescriptions(replacements,
                Map.of(bindingKey, location("controller/main/idle/transition", ProgramRole.CONTROLLER_TRANSITION, ""),
                    storageKey, location("animation/main/idle/blend-weight", ProgramRole.ANIMATION_SAMPLE, ""),
                    arityKey, location("animation/main/idle/instruction", ProgramRole.ANIMATION_INSTRUCTION, "0"))));
        assertThrows(IllegalArgumentException.class,
            () -> BuiltinModelIndexTool.replacementDescriptions(replacements, Map.of()));
    }

    private static ModelProgramInventory.SemanticLocation location(String value, ProgramRole role,
        String discriminator) {
        return new ModelProgramInventory.SemanticLocation("player", value, role, discriminator);
    }

    private static Hash256 hash(int marker) {
        var bytes = new byte[Hash256.SIZE];
        bytes[bytes.length - 1] = (byte)marker;
        return new Hash256(bytes);
    }
}
