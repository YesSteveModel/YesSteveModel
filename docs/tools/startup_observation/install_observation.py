"""Apply the observation overlay to an isolated copy of the current Java sources."""
import argparse
from pathlib import Path

HELPER = "com.elfmcys.ysm.verification.StartupObservation"
STARTUP_PATH = Path("client/ClientStartup.java")
STARTUP_CALL = "ClientModelService.start(system, builtinCapabilities.availability());"
OBSERVED_STARTUP_CALL = f"{HELPER}.published({STARTUP_CALL.removesuffix(';')});"


def replacement(text, old, new, path):
    if text.count(old) != 1:
        raise ValueError(f"overlay anchor must occur once: {path}: {old!r}")
    return text.replace(old, new)


def replace(path, old, new):
    text = path.read_text(encoding="utf-8")
    path.write_text(replacement(text, old, new, path), encoding="utf-8", newline="\n")


def install(root, numeric=True):
    java = root / "src/main/java/com/elfmcys/ysm"
    destination = java / "verification/StartupObservation.java"
    if destination.exists():
        raise ValueError("observation already installed")
    patches = {}

    def patch(path, old, new):
        text = patches[path] if path in patches else path.read_text(encoding="utf-8")
        patches[path] = replacement(text, old, new, path)

    patch(java / "event/CommonEvent.java",
            "public static void onSetupEvent(FMLCommonSetupEvent event) {",
            "public static void onSetupEvent(FMLCommonSetupEvent event) {\n"
            f'        {HELPER}.boundary("COMMON_SETUP");')
    patch(java / STARTUP_PATH, STARTUP_CALL, OBSERVED_STARTUP_CALL)
    patch(java / "model/service/ClientModelService.java", "new ExpressionEngineMetrics()",
            f"{HELPER}.metrics()")
    loader = java / "model/resource/client/render/ModelRenderTargetLoader.java"
    text = loader.read_text(encoding="utf-8")
    start = text.index("private PreparedSet prepareSet(")
    end = text.index("private static StringDataProto mergeFunctions", start)
    section = text[start:end]
    anchor = "try (var resources = new ResourceTransaction()) {"
    if section.count(anchor) != 1:
        raise ValueError("prepareSet observation anchor changed")
    section = section.replace(anchor, f"try (var observation = new {HELPER}.Scope(request.targetId() + \"/\" + set.id(), request.defaultModel());\n"
                              "                 var resources = new ResourceTransaction()) {")
    patches[loader] = text[:start] + section + text[end:]
    metrics = java / "expression/aot/ExpressionEngineMetrics.java"
    if numeric:
        patch(metrics, "stageCalls.incrementAndGet(stage.ordinal());",
                f'{HELPER}.duration(stage.name(), nanoseconds);\n        stageCalls.incrementAndGet(stage.ordinal());')
        patch(metrics, "analysisWork.addAndGet(kind.ordinal() * ANALYSIS_COUNTER_COUNT + counter.ordinal(), delta);",
                f'{HELPER}.analysis(kind.name(), counter.name(), delta);\n            '
                'analysisWork.addAndGet(kind.ordinal() * ANALYSIS_COUNTER_COUNT + counter.ordinal(), delta);')
    patch(metrics, "portableProgramDecodes.incrementAndGet();",
            f'{HELPER}.work("PORTABLE_PROGRAM_DECODE");\n        portableProgramDecodes.incrementAndGet();')
    patches[destination] = Path(__file__).with_name("StartupObservation.java").read_text(encoding="utf-8")
    for path, content in patches.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8", newline="\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("project", type=Path)
    parser.add_argument("--without-numeric", action="store_true", help="Boundary-only original arm for observer overhead pilot")
    args = parser.parse_args()
    install(args.project.resolve(), not args.without_numeric)
