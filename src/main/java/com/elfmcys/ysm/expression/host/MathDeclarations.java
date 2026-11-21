package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.api.molang.MolangEffect;
import dev.semaweave.BuiltinType;
import dev.semaweave.SymbolKey;
import dev.semaweave.registry.MathIntrinsic;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** Language names reference closed engine operations; Java providers do not declare these symbols. */
public final class MathDeclarations {
    private MathDeclarations() {}

    public record Declaration(String name, MathIntrinsic operation) {
        public SymbolKey key() {
            return new SymbolKey("math:" + name);
        }

        public BuiltinSurface surface() {
            return new BuiltinSurface(key().canonical(), key().canonical(), BuiltinType.F32, operation.arity(),
                operation.arity(), Collections.nCopies(operation.arity(), BuiltinType.F32), Optional.empty(), List.of(),
                MolangEffect.CONTEXT, List.of(), "", "", "");
        }
    }

    public static final List<Declaration> ALL =
        List.of(new Declaration("abs", MathIntrinsic.ABS), new Declaration("clamp", MathIntrinsic.CLAMP),
            new Declaration("cos", MathIntrinsic.COS_DEGREES), new Declaration("exp", MathIntrinsic.EXP),
            new Declaration("floor", MathIntrinsic.FLOOR), new Declaration("lerp", MathIntrinsic.LERP),
            new Declaration("max", MathIntrinsic.MAX), new Declaration("min", MathIntrinsic.MIN),
            new Declaration("mod", MathIntrinsic.REMAINDER), new Declaration("pow", MathIntrinsic.POW),
            new Declaration("round", MathIntrinsic.ROUND), new Declaration("sin", MathIntrinsic.SIN_DEGREES),
            new Declaration("sqrt", MathIntrinsic.SQRT), new Declaration("min_angle", MathIntrinsic.WRAP_DEGREES));
}
