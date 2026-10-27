package com.elfmcys.ysm.expression.inventory;

import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;

/** Expression typing follows the animation file's declared execution set. */
public enum ExpressionSet {
    MAIN("main"), FP_ARM("fp_arm");

    private final String id;

    ExpressionSet(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public ModelDataProto select(ModelDataProto source) {
        var result = ModelDataProto.newBuilder().putAllAnimationControllers(source.animationControllers());
        source.animationFiles().forEach((name, file) -> {
            if (name.equals("fp_arm") == (this == FP_ARM))
                result.putAnimationFiles(name, file);
        });
        return result.build();
    }
}
