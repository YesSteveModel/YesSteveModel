package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.format.parser.pojo.manifest.ModelManifest;
import com.elfmcys.ysm.format.parser.pojo.manifest.metadata.ModelMetadata;
import com.elfmcys.ysm.format.parser.pojo.manifest.settings.ConfigForms;
import com.elfmcys.ysm.format.parser.pojo.manifest.settings.ExtraAnimationButton;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.proto.mixel.manifest.info.ConfigFormsProto;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelInfoBuilderTest {
    @Test
    void leavesNullAndEmptyConfigFormLabelsAbsent() throws Exception {
        var nullLabels = new ConfigForms();
        nullLabels.value = "v.mode";
        nullLabels.labels = null;
        assertTrue(writeForm(nullLabels).labels().isEmpty());

        var emptyLabels = new ConfigForms();
        emptyLabels.value = "v.mode";
        emptyLabels.labels = new LinkedHashMap<>();
        assertTrue(writeForm(emptyLabels).labels().isEmpty());
    }

    @Test
    void preservesConfigLabelDeclarationOrder() throws Exception {
        var source = new ConfigForms();
        source.value = "v.mode";
        source.labels.put("low", "v.mode=0");
        source.labels.put("high", "v.mode=1");

        var result = writeForm(source);

        assertFalse(result.labels().isEmpty());
        assertEquals(2, result.labels().size());
        assertEquals("low", result.labels().get(0).name());
        assertTrue(result.labels().get(0).actionProgram().bytecode().hasRemaining());
        assertEquals("high", result.labels().get(1).name());
        assertTrue(result.labels().get(1).actionProgram().bytecode().hasRemaining());
    }

    private static ConfigFormsProto writeForm(ConfigForms form) throws Exception {
        var source = new ModelManifest();
        source.metadata = new ModelMetadata();
        var button = new ExtraAnimationButton();
        button.configForms.add(form);
        source.properties.extraAnimationButtons.add(button);
        var builder = ModelInfoBuilder.buildInfo(true, source, (path, hashType, policy, consumer) -> {
        }, new com.elfmcys.ysm.expression.frontend.MolangFrontend());
        ModelInfoBuilder.setProperties(builder, source.properties, "test", new Hash256(new byte[Hash256.SIZE]));
        var info = builder.build();

        return info.settings().extraAnimationButtons().get(0).configForms().get(0);
    }
}
