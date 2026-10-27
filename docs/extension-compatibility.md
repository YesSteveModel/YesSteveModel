# 扩展模组兼容性检测

YSM 主 JAR 内置 `@YsmExtension`、`@YsmEventHandler` 和兼容性注解处理器。处理器在 `javac` 完成每个 class 的生成后读取实际字节码，收集扩展入口依赖的 YSM 类、方法、构造器和字段，并生成不直接链接 YSM 的启动期 checker。扩展最终仍须经过 Forge/NeoForge 的标准 reobf；不需要 YSM 专用 Gradle 插件或额外的 Processor 制品。

## 接入

同一个 YSM 主制品既作为模组编译依赖，也放入 `annotationProcessor`：

```groovy
def ysmDependency = "com.elfmcys.ysm:ysm:<ysm-version>"

dependencies {
    modCompileOnly ysmDependency
    annotationProcessor ysmDependency
}
```

上例的 `modCompileOnly` 适用于 NeoForge ModDev/LegacyForge。使用 ForgeGradle 时，将该行换成 `compileOnly fg.deobf(ysmDependency)`；`annotationProcessor` 必须使用未经 `fg.deobf` 包装的同一主制品。这里的主制品是正常发布的无 classifier 最终 JAR，不能改用开发期 `raw` JAR。Processor 所需 ASM 已用 YSM 私有包名内嵌，不依赖扩展项目或 Forge 在 Processor classpath 上额外提供 ASM。

扩展的 `mods.toml` 仍应把 YSM 声明为可选且排在本模组之前初始化：

```toml
[[dependencies.<extension-mod-id>]]
modId="ysm"
versionRange = "[3.0,)"
mandatory=false
ordering="AFTER"
side="BOTH"
```

处理器不会修改或验证模组元数据。

然后标注有引用 ysm 符号的类或方法：

```java
@YsmExtension(side = YsmExtension.Side.CLIENT)
public void installRenderer() {
    // 可以通过 lambda、方法引用和自有 helper 间接调用 YSM。
}
```

也可对整个编译任务设置 `-Aysm.compat.ownedPackages=com.example.shared,com.example.feature`。只声明包前缀但不为对应模块生成 body 指纹，无法覆盖 Gradle 因纯方法体变化而完全不启动 javac 的情况。

## 生成接口

```java
var result = MyIntegrationCompatibilityChecker.checkInstallRenderer();
if (!result.isCompatible() || !result.coverageComplete()) {
    // 禁用联动功能并报告 result.issues()
    return;
}
```

类标注生成 `MyIntegrationCompatibilityChecker.check()`。调用 checker 的 bootstrap 类本身不得在继承树、字段或方法签名中引用 YSM；否则 JVM 可能在执行 checker 前就链接失败。

## Molang 声明

同一发布主 JAR 同时提供公共注解与 JDK-only `UnifiedDeclarationProcessor`；无需额外 processor 制品。auto `@YsmMolangExtension` 自带类级 CLIENT 完整 checker，成员不追加依赖。签名、值和生命周期见[共同声明契约](architecture/animation/molang-declarations.md)。

默认 auto 由 YSM 收集、前检并冻结。选择 `mode = MolangAdmissionMode.MANUAL` 时，独立 bootstrap 完成自由前检后，在 client 服务冻结前提交：

```java
String resource = "META-INF/ysm/molang/com.example.Adapter.bin";
MolangDeclarations.submit(resource, adapterSourceUrl, compatible, detail);
```

`adapterSourceUrl` 是该 AP resource 的 URL，不是实现类 Class；bootstrap 仍不得提前链接实现/optional subject。票据不绕过 source ownership、共同声明验证或 all-of 依赖；同类未提交/重复、auto/manual 双路、冲突及冻结后提交均拒绝，不覆盖先到者。接纳失败不发布对应 Molang 符号。完整 checker 算法与事件 handler 契约不因声明收敛改变。

## 自动注册事件处理器

需要监听 YSM 自有事件总线时，可用 `@YsmEventHandler` 代替类级
`@YsmExtension` 和手动调用模组主类的 `registerEventHandler(...)`：

```java
@YsmEventHandler(side = Side.CLIENT)
public final class MyMod {
    @SubscribeEvent
    public void onRender(RenderModelEvent event) {
        //
    }
}
```

`@YsmEventHandler` 只允许标注 public、非 abstract 的顶层类或 public static
成员类，并要求 public 无参构造器。它在编译期完全继承类级 `@YsmExtension`
的检测语义，因此同一个类不能再同时标注类级 `@YsmExtension`。

Forge 完成模组加载时，YSM 从扫描元数据中取得处理器类名，此时不会加载处理器类。
YSM 先调用对应 `<ClassName>CompatibilityChecker.check()`：

- `COMPATIBLE`：实例化并注册；
- `COMPATIBLE_WITH_WARNINGS` 或覆盖不完整：记录完整 issues 后仍注册；
- `NOT_APPLICABLE`、YSM 不可用、硬不兼容或检查失败：跳过该处理器；
- 缺少生成的 checker：跳过并提示扩展把 YSM 主 JAR 加入 `annotationProcessor`。

每个处理器独立失败；一个处理器的链接、构造或注册异常不会阻止其他处理器继续注册。
模组主类的 `registerEventHandler(Object)` 仍保留，供确实需要自行控制实例生命周期的代码使用。

自动注册目标是 YSM 的模组事件总线，不是 `MinecraftForge.EVENT_BUS`。注册发生在
`FMLLoadCompleteEvent` 期间，因此处理器只会收到此后发布的 YSM 运行时事件，不会补收
已经发生的 Forge/FML 生命周期事件，也不会收到触发注册的这次 load-complete 事件。

结果状态包括：

- `COMPATIBLE`：全部静态可见符号存在，且覆盖完整；
- `COMPATIBLE_WITH_WARNINGS`：符号存在，但存在未知动态目标、Mixin 或不可读取的自有代码等覆盖缺口；
- `NOT_APPLICABLE`：客户端 checker 在专用服务端上被跳过；
- `YSM_NOT_LOADED` / `YSM_UNAVAILABLE`：未加载 YSM，或模组主类的 `isAvailable()` 返回 `false`；
- `INCOMPATIBLE`：检测到缺失或签名不匹配；
- `CHECK_FAILED`：manifest、运行环境或 reobf 探针本身不可信。此状态必须按不兼容处理。

`SERVER` 表示 checker 应从服务端 bootstrap 调用。它不会在物理客户端上自动跳过，因为集成服务端也运行在客户端发行版内。`CLIENT` 会在专用服务端返回 `NOT_APPLICABLE`，避免解析客户端类型探针。

## 检测范围

方法标注始终检测当前类及自有继承树的父类/接口、字段类型、普通方法签名和 YSM override 声明；方法体从同名的全部标注重载出发，继续遍历当前 javac 输出和显式自有包中的调用目标。

类级 `@YsmExtension` 和 `@YsmEventHandler` 在上述结构检测之外，会以当前类及自有继承树的全部方法体作为调用图根。

字节码扫描包括：

- 普通类、字段、方法和构造器指令；
- class literal、栈帧、异常类型、数组和泛型签名中的 YSM 类型；
- `invokedynamic` 的 call-site descriptor、bootstrap `Handle` 和全部递归参数；
- lambda/metafactory 实现 handle、method type、bridge/marker 参数；
- `ConstantDynamic` 及其中嵌套的 handle/constant。

已知 JDK bootstrap 会完整收集其静态可见依赖。未知 bootstrap 不会在编译期或启动检查时执行；checker 返回 `COMPATIBLE_WITH_WARNINGS` 和 `DYNAMIC_TARGET_UNKNOWN`，因为 bootstrap 可以任意构造运行时 `CallSite`。

对自有代码的 `invokevirtual` / `invokeinterface`，处理器会扫描编译时可解析的声明实现；若目标类或方法仍可被覆写，则同时报告 `DYNAMIC_DISPATCH_UNKNOWN`。将关键 helper 设为 `final`、`private` 或 `static` 可以把这部分调用图闭合。

Mixin 类或方法会产生 `MIXIN_RUNTIME_TRANSFORM` 覆盖告警。处理器可以扫描注入方法自身的字节码，但无法证明 Mixin 应用后的最终运行时调用图。
