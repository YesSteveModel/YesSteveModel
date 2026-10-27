package com.elfmcys.ysm.expression.inventory;

import com.elfmcys.ysm.expression.host.ExpressionHost;
import dev.semaweave.BuiltinType;
import dev.semaweave.EntryResult;
import dev.semaweave.LocalName;
import dev.semaweave.ProgramEntrySignature;
import dev.semaweave.ProgramShape;
import dev.semaweave.RequiredParameter;
import dev.semaweave.SymbolKey;
import dev.semaweave.TopLevelEntrySignature;
import dev.semaweave.UnionType;
import dev.semaweave.UserFunctionEntrySignature;
import dev.semaweave.ValueType;

import java.util.List;

/** Closed role table for every persistent expression admitted by the model schema. */
public enum ProgramRole {
    ANIMATION_SAMPLE(ProgramShape.F32, BuiltinType.F32, null), ANIMATION_INSTRUCTION(ProgramShape.VOID, null, null),
    CONTROLLER_ANIMATION(ProgramShape.BOOLEAN, BuiltinType.BOOL, null),
    CONTROLLER_TRANSITION(ProgramShape.BOOLEAN, BuiltinType.BOOL, null),
    CONTROLLER_ON_ENTRY(ProgramShape.VOID, null, null), CONTROLLER_ON_EXIT(ProgramShape.VOID, null, null),
    EVENT_PLAYER_INIT(ProgramShape.VOID, null, null), EVENT_PLAYER_UPDATE(ProgramShape.VOID, null, BuiltinType.BOOL),
    EVENT_SYNC(ProgramShape.VOID, null, ExpressionHost.FLOAT_LIST),
    EVENT_DEFER(ProgramShape.VOID, null, ExpressionHost.FLOAT_LIST),
    EVENT_CODED_CONTROLLER(ProgramShape.F32, BuiltinType.F32, null),
    CONFIG_READ_BOOL(ProgramShape.BOOLEAN, BuiltinType.BOOL, null),
    CONFIG_READ_F32(ProgramShape.F32, BuiltinType.F32, null),
    CONFIG_WRITE_BOOL(ProgramShape.VOID, null, BuiltinType.BOOL),
    CONFIG_WRITE_F32(ProgramShape.VOID, null, BuiltinType.F32), CONFIG_LABEL_ACTION(ProgramShape.VOID, null, null),
    USER_FUNCTION(ProgramShape.USER_FUNCTION, scalarValueType(), null);

    private final ProgramShape shape;
    private final ValueType resultType;
    private final ValueType parameterType;

    ProgramRole(ProgramShape shape, ValueType resultType, ValueType parameterType) {
        this.shape = shape;
        this.resultType = resultType;
        this.parameterType = parameterType;
    }

    public ProgramEntrySignature signature(String callable, List<LocalName> remainingLocals) {
        if (this == USER_FUNCTION) {
            return UserFunctionEntrySignature.inferred(new SymbolKey(callable), new LocalName("args"),
                scalarValueType(), without(remainingLocals, "args"));
        }
        var parameterName = isEvent() ? "args" : "value";
        var parameters = parameterType == null ? List.<RequiredParameter>of()
            : List.of(new RequiredParameter(new LocalName(parameterName), parameterType));
        var result = shape == ProgramShape.VOID ? new EntryResult.Void() : new EntryResult.Value(resultType);
        return new TopLevelEntrySignature(shape, result, parameters, without(remainingLocals, parameterName));
    }

    public ProgramShape shape() {
        return shape;
    }

    public dev.semaweave.EntryPermissions entryPermissions() {
        return switch (this) {
            case ANIMATION_SAMPLE, CONTROLLER_ANIMATION, CONFIG_READ_BOOL, CONFIG_READ_F32 ->
                dev.semaweave.EntryPermissions.CONTEXT;
            default -> dev.semaweave.EntryPermissions.ACTION;
        };
    }

    public boolean isEvent() {
        return switch (this) {
            case EVENT_PLAYER_INIT, EVENT_PLAYER_UPDATE, EVENT_SYNC, EVENT_DEFER, EVENT_CODED_CONTROLLER -> true;
            default -> false;
        };
    }

    public String fallbackClassifier() {
        return switch (shape) {
            case F32 -> "typed-zero:f32";
            case BOOLEAN -> "typed-zero:bool";
            case VOID -> "typed-zero:void";
            case USER_FUNCTION -> "typed-zero:f32";
            case VALUE -> "typed-zero:unset";
        };
    }

    private static List<LocalName> without(List<LocalName> locals, String parameter) {
        return locals.stream().filter(value -> !value.name().equals(parameter)).toList();
    }

    public static UnionType scalarValueType() {
        return new UnionType(List.of(BuiltinType.UNSET, BuiltinType.BOOL, BuiltinType.F32, BuiltinType.STRING));
    }
}
