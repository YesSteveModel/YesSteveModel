# 扩展模组兼容性检测

YSM 主 JAR 内置了 `@YsmExtension`、`@YsmEventHandler` 以及兼容性注解处理器。该处理器在 `javac` 生成每个 class 文件后读取实际字节码，收集扩展入口依赖的 YSM 类、方法、构造器和字段，并生成一个不直接链接 YSM 的启动期 checker。扩展模组最终仍需经过 Forge 或 NeoForge 的标准 reobf，但无需引入 YSM 专用 Gradle 插件或额外的 Processor 制品。

## 接入

将同一个 YSM 主制品同时加入模组编译依赖和 `annotationProcessor`：

```groovy
def ysmDependency = "com.elfmcys.ysm:ysm:<ysm-version>"

dependencies {
    modCompileOnly ysmDependency
    annotationProcessor ysmDependency
}
```

上例中的 `modCompileOnly` 适用于 NeoForge ModDev 和 LegacyForge。如果使用 ForgeGradle，请将该行替换为 `compileOnly fg.deobf(ysmDependency)`，而 `annotationProcessor` 仍须使用未经 `fg.deobf` 包装的同一主制品。这里的主制品指正常发布的无 classifier 最终 JAR，不能改用开发期 `raw` JAR。处理器所需的 ASM 已按 YSM 私有包名内嵌，不依赖扩展项目或 Forge 在 processor classpath 上额外提供 ASM。

扩展模组的 `mods.toml` 仍应将 YSM 声明为可选依赖，并排在本模组之前初始化：

```toml
[[dependencies.<extension-mod-id>]]
modId="ysm"
versionRange = "[3.0,)"
mandatory=false
ordering="AFTER"
side="BOTH"
```

注解处理器不会修改或校验 `mods.toml` 中的模组元数据。

随后，在引用了 YSM 符号的类或方法上添加注解：

```java
@YsmExtension(side = YsmExtension.Side.CLIENT)
public void installRenderer() {
    // 可以通过 lambda、方法引用和自有 helper 间接调用 YSM。
}
```

也可以为整个编译任务配置 `-Aysm.compat.ownedPackages=com.example.shared,com.example.feature`。注意，如果只声明包前缀而不为对应模块生成方法体指纹，当 Gradle 因仅修改方法体而跳过 `javac` 时，处理器将无法重新运行。

## 生成接口

```java
var result = MyIntegrationCompatibilityChecker.checkInstallRenderer();
if (!result.isCompatible() || !result.coverageComplete()) {
    // 禁用联动功能并报告 result.issues()
    return;
}
```

类级标注会生成 `MyIntegrationCompatibilityChecker.check()`。调用 checker 的 bootstrap 类本身不得在继承树、字段类型或方法签名中引用任何 YSM 类型，否则 JVM 可能在执行 checker 之前就触发链接失败。

## Molang 声明

同一发布主 JAR 同时提供 Molang 公共注解和纯 JDK 实现的 `UnifiedDeclarationProcessor`，无需额外引入 processor 制品。签名、值类型与生命周期要求见[共同声明契约](architecture/animation/molang-declarations.md)。

扩展声明支持两种接纳模式：

- **默认 `AUTO` 模式**：`@YsmMolangExtension` 会自带类级 `CLIENT` 完整 checker，成员本身不额外追加依赖；YSM 在加载阶段自动收集声明、执行前置检查并在客户端服务冻结时发布。
- **`MANUAL` 模式**：选择 `mode = MolangAdmissionMode.MANUAL` 时，由独立的 bootstrap 类自行完成前置检查，并在客户端服务冻结前提交检查结果：

```java
String resource = "META-INF/ysm/molang/com.example.Adapter.bin";
MolangDeclarations.submit(resource, adapterSourceUrl, compatible, detail);
```

使用 `MANUAL` 模式时需遵守以下边界：

- `adapterSourceUrl` 必须传入该注解处理器生成的资源 URL，而不是实现类的 `Class` 对象；bootstrap 仍然不得提前链接实现类或可选依赖目标。
- 手动提交票据不会绕过来源归属校验、共同声明校验或 all-of 依赖检查。
- 同一个类未提交、重复提交、同时走 `AUTO` 与 `MANUAL` 两条路径、提交内容冲突，或在服务冻结后才提交，都会被拒绝，且不会覆盖先到达的记录。
- 任一接纳步骤失败时，对应的 Molang 符号都不会发布。
- 完整的 checker 算法与事件处理器契约不会因 Molang 声明统一而改变。

## 自动注册事件处理器

如果扩展需要监听 YSM 自有事件总线，可以用 `@YsmEventHandler` 代替类级 `@YsmExtension` 和手动调用模组主类的 `registerEventHandler(...)`：

```java
@YsmEventHandler(side = Side.CLIENT)
public final class MyMod {
    @SubscribeEvent
    public void onRender(RenderModelEvent event) {
        //
    }
}
```

`@YsmEventHandler` 只允许标注 `public`、非 `abstract` 的顶层类或 `public static` 成员类，并且必须提供 `public` 无参构造器。它在编译期完全沿用类级 `@YsmExtension` 的字节码检测语义，因此同一个类不能同时标注 `@YsmEventHandler` 和类级 `@YsmExtension`。

Forge 完成模组加载时，YSM 从扫描元数据中获取处理器类名（此时不会加载处理器类），然后先调用对应的 `<ClassName>CompatibilityChecker.check()`，按返回状态决定后续动作：

- `COMPATIBLE`：实例化并注册处理器；
- `COMPATIBLE_WITH_WARNINGS` 或覆盖不完整：记录完整的 issues 列表后仍然注册；
- `NOT_APPLICABLE`、YSM 不可用、硬不兼容或检查失败：跳过该处理器；
- 缺少生成的 checker：跳过该处理器，并提示扩展方把 YSM 主 JAR 加入 `annotationProcessor`。

各个处理器的注册彼此独立：单个处理器在链接、构造或注册阶段抛出异常，不会阻止其余处理器继续注册。如果某个处理器在注册过程中失败，YSM 会尝试在原总线上撤销该实例；若撤销也失败，则会连同原始失败原因一并报告（此时总线上仍可能残留监听）。模组主类的 `registerEventHandler(Object)` 依然保留，供确实需要自行管理实例生命周期的代码使用。

自动注册的目标是 YSM 自己的模组事件总线，而不是 `MinecraftForge.EVENT_BUS`。由于注册发生在 `FMLLoadCompleteEvent` 期间，处理器只会收到此后发布的 YSM 运行时事件，不会补收此前已经触发的 Forge 或 FML 生命周期事件，也不会收到触发注册的那次 load-complete 事件。

checker 返回的结果状态包括：

- `COMPATIBLE`：全部静态可见符号均存在，且检测覆盖完整；
- `COMPATIBLE_WITH_WARNINGS`：已发现的符号均存在，但存在未知动态目标、Mixin 或无法读取的自有代码等覆盖缺口；
- `NOT_APPLICABLE`：客户端 checker 在专用服务端上被跳过；
- `YSM_NOT_LOADED` 或 `YSM_UNAVAILABLE`：未加载 YSM，或模组主类的 `isAvailable()` 返回 `false`；
- `INCOMPATIBLE`：检测到符号缺失或签名不匹配；
- `CHECK_FAILED`：manifest、运行环境或 reobf 探针本身不可信，调用方必须按不兼容处理。

`SERVER` 表示该 checker 应从服务端 bootstrap 调用，它在物理客户端上不会自动跳过（因为客户端发行版内也包含集成服务端）。`CLIENT` 则会在专用服务端直接返回 `NOT_APPLICABLE`，避免解析客户端专属类型探针。

## 检测范围

方法级标注始终会检测当前类及其自有继承树的父类与接口、字段类型、普通方法签名和 YSM override 声明；方法体分析则从同名的全部标注重载出发，继续遍历当前 `javac` 输出目录及显式声明的自有包中的调用目标。

类级 `@YsmExtension` 和 `@YsmEventHandler` 在上述结构检测之外，还会把当前类及自有继承树的全部方法体作为调用图起点。

字节码扫描覆盖以下引用形式：

- 普通类、字段、方法和构造器指令；
- class literal、栈帧、异常类型、数组以及泛型签名中的 YSM 类型；
- `invokedynamic` 的 call-site descriptor、bootstrap `Handle` 以及全部递归参数；
- lambda 与 metafactory 的实现 handle、method type，以及 bridge 与 marker 参数；
- `ConstantDynamic` 及其中嵌套的 handle 与常量。

对于已知的 JDK bootstrap，处理器会完整收集其静态可见依赖；对于未知的 bootstrap，处理器不会在编译期或启动检查时执行它，checker 会返回 `COMPATIBLE_WITH_WARNINGS` 和 `DYNAMIC_TARGET_UNKNOWN`，因为未知 bootstrap 可以在运行时构造任意 `CallSite`。

对于自有代码的 `invokevirtual` 和 `invokeinterface`，处理器会扫描编译时可解析的声明实现；如果目标类或方法仍可被子类覆写，还会同时报告 `DYNAMIC_DISPATCH_UNKNOWN`。将关键 helper 设为 `final`、`private` 或 `static` 可以消除这部分不确定的动态分派。

Mixin 类或方法会触发 `MIXIN_RUNTIME_TRANSFORM` 覆盖告警：处理器可以扫描注入方法自身的字节码，但无法证明 Mixin 应用后的最终运行时调用图。
