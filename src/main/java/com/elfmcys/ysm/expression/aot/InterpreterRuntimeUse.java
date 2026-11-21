package com.elfmcys.ysm.expression.aot;

import com.elfmcys.ysm.expression.host.MolangContext;
import dev.semaweave.interpreter.InterpreterEnvironment;
import dev.semaweave.interpreter.InterpreterInstance;
import dev.semaweave.interpreter.InterpreterProgram;
import dev.semaweave.interpreter.InterpreterRoamingState;

/**
 * Owns one private instance and its active shared-program use. The installation owner serializes execution and close;
 * close ends the instance before returning its shared use. Returned programs are borrowed only for one invocation.
 */
public interface InterpreterRuntimeUse extends AutoCloseable {
    InterpreterInstance<MolangContext> instance();

    InterpreterEnvironment<MolangContext> environment();

    InterpreterProgram program(int sourceId) throws Exception;

    InterpreterRoamingState roamingState();

    @Override
    void close();
}
