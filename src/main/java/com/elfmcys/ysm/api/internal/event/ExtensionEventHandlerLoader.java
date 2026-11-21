package com.elfmcys.ysm.api.internal.event;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.api.annotation.YsmEventHandler;
import java.lang.annotation.ElementType;
import java.util.List;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.forgespi.language.ModFileScanData;

/**
 * Internal Forge discovery and registration bridge for {@link YsmEventHandler}.
 *
 * <p>
 * Execution: discovery and registration run in the load-complete listener's invocation window. Registration does not
 * grant render-thread access to constructors or later callbacks.
 *
 * <p>
 * Failure: qualification failures are isolated per handler. Failed registration is rolled back on the same bus and
 * handler instance. A rollback failure is retained with the original error; listeners may remain installed.
 *
 * <p>
 * Seam: implements [optional handler admission](docs/architecture/integration/overview.md#失败隔离与声明寿命); see the [rollback
 * verification boundary](docs/status/known-issues/integration.md#可选-handler-注册回滚).
 */
public final class ExtensionEventHandlerLoader {
    private static final String ANNOTATION_NAME = YsmEventHandler.class.getName();

    private ExtensionEventHandlerLoader() {}

    /**
     * Attaches one load-complete registration callback to the supplied bus. The bootstrap caller invokes this once per
     * intended bus; this method does not deduplicate repeated attachment or own bus shutdown.
     */
    public static void attach(IEventBus eventBus) {
        eventBus.addListener((FMLLoadCompleteEvent event) -> registerAll(eventBus));
    }

    private static void registerAll(IEventBus eventBus) {
        List<String> handlerNames;
        try {
            handlerNames = discoverHandlerNames();
        } catch (RuntimeException | LinkageError exception) {
            YesSteveModel.LOGGER.error("Failed to discover @YsmEventHandler classes", exception);
            return;
        }

        ClassLoader loader = ExtensionEventHandlerLoader.class.getClassLoader();
        if (loader == null) {
            loader = ClassLoader.getSystemClassLoader();
        }
        for (String handlerName : handlerNames) {
            registerOne(eventBus, handlerName, loader);
        }
    }

    private static List<String> discoverHandlerNames() {
        return discoverHandlerNames(ModList.get().getAllScanData());
    }

    static List<String> discoverHandlerNames(List<ModFileScanData> scanData) {
        return scanData.stream().flatMap(fileScan -> fileScan.getAnnotations().stream())
            .filter(annotation -> annotation.targetType() == ElementType.TYPE)
            .filter(annotation -> ANNOTATION_NAME.equals(annotation.annotationType().getClassName()))
            .map(ModFileScanData.AnnotationData::memberName).filter(name -> name != null && !name.isBlank()).distinct()
            .sorted().toList();
    }

    static void registerOne(IEventBus eventBus, String handlerName, ClassLoader loader) {
        ExtensionEventHandlerRegistration.Outcome outcome;
        try {
            outcome = ExtensionEventHandlerRegistration.prepare(handlerName, loader);
        } catch (ExtensionEventHandlerRegistration.Failure failure) {
            if (failure.stage() == ExtensionEventHandlerRegistration.Stage.CHECKER_MISSING) {
                YesSteveModel.LOGGER.error("Skipping @YsmEventHandler {} because checker {} is missing. "
                    + "Add the classifierless YSM JAR to annotationProcessor.", handlerName, failure.subject());
            } else {
                YesSteveModel.LOGGER.error("Skipping @YsmEventHandler {}: {} ({})", handlerName, failure.getMessage(),
                    failure.subject(), failure.getCause());
            }
            return;
        }

        if (!outcome.shouldRegister()) {
            YesSteveModel.LOGGER.warn("Skipping @YsmEventHandler {}: status={}, coverageComplete={}, issues={}",
                handlerName, outcome.status(), outcome.coverageComplete(), outcome.issues());
            return;
        }
        if (outcome.hasWarnings()) {
            YesSteveModel.LOGGER.warn(
                "Registering @YsmEventHandler {} with compatibility warnings: "
                    + "status={}, coverageComplete={}, issues={}",
                handlerName, outcome.status(), outcome.coverageComplete(), outcome.issues());
        }

        try {
            eventBus.register(outcome.handler());
        } catch (RuntimeException | LinkageError exception) {
            try {
                eventBus.unregister(outcome.handler());
            } catch (RuntimeException | LinkageError rollbackFailure) {
                if (rollbackFailure != exception)
                    exception.addSuppressed(rollbackFailure);
                YesSteveModel.LOGGER.error(
                    "Failed to register @YsmEventHandler {} and roll back its listeners; residual listeners may remain",
                    handlerName, exception);
                return;
            }
            YesSteveModel.LOGGER.error("Failed to register @YsmEventHandler {}; rolled back its listeners", handlerName,
                exception);
            return;
        }
        YesSteveModel.LOGGER.info("Registered @YsmEventHandler {}", handlerName);
    }
}
