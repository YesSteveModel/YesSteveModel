# 验证边界

本页定义验证的分层职责、投入尺度与单测维护红线。产品承诺与优化优先级以[产品决策](../product-decisions/README.md)为准。

## 性能验证

[工作量与服务端软目标](../product-decisions/decisions/workload-budget.md)与[基础设备参考](../product-decisions/decisions/device-quality.md)用于横向比较优化收益，不作为日常变更的硬门槛。300 人压测仅用于探测上限，不承诺在该规模稳定服务。

报告帧率、MSPT 或 lag spike 时，须附带内容规模与运行环境；未跑完完整压测不记作性能达标。

## 视觉验证

固定场景与人机检查分工见[视觉一致性验收](../standards/conformance.md#视觉一致性验收)。

## 验证分层架构

| 层级 | 默认入口 | 耗时目标 | 负责证明 |
|---|---|---|---|
| L0 静态门禁 | `./gradlew test`、`shadowJar` 校验 | < 1 秒 | Dedicated Server 字节码不引用客户端类、发布包不含测试控制面、注解处理器产物契约 |
| L1 确定性契约单测 | `./gradlew test` / `./gradlew serverMockTest` | < 30 秒 | 格式/协议与畸形输入、AOT 与解释器等价、缓存原子发布、资源租约归属；`serverMockTest` 覆盖百人模拟传输并发/背压/重载不变量 |
| L2 无头负载与微基准 | `serverScaleLoad` / `benchmark`（按需 + JFR） | 1–5 分钟 | 100/200/300 人并发 + 重载 + Netty 水位背压下的 MSPT 增量与 lag spike；Molang/JNI 微基准 |
| L3 真机 Forge 冒烟 | `runServer` + `runClient`（发版/宿主改动按需） | 2–5 分钟 | Forge 频道握手、真实登录/重载/切模/断线重连、主循环渲染与音频稳定、基础视觉一致性 |

各层只证明其声明边界内的行为，不互相替代。

## 单元测试维护规则

1. **契约优先，禁测实现细节**：断言只依赖公开 API 或 Seam 可观察输出、状态迁移与资源释放。禁止反射读写私有成员、正则断言生成字节码内部名、保留“证明旧重载已删”的墓碑测试或常量自证测试。
2. **禁止伪造 Minecraft / Forge / OpenGL 宿主**：凡需 `Unsafe.allocateInstance(Minecraft.class)`、反射改写 `Bootstrap`/`RenderSystem` 静态字段或改写 `ClassLoader` 才能运行的测试不属于 L1；剥离无宿主纯逻辑放 L1，宿主交互交由 L3。
3. **禁止面向测试污染生产代码**：不得为单测添加多参数测试专构、无生产多实现的策略接口、生产零实现的故障注入 `Hook` 或白盒计数方法。替身只允许从显式架构 Seam（如 `TransportPort`）注入。
4. **按信任边界投入畸形输入测试**：畸形输入与异常路径测试聚焦跨信任边界（网络协议帧、资产容器/旧格式解码、用户输入与第三方扩展），不把内部 Seam 前置条件逐条翻译为单测。
5. **确定性与体积控制**：L1 默认可通过 `./gradlew test` 确定性运行（显式标注的 JNI 边界测试除外，不依赖隐式环境变量），禁止 `System.gc()` 或长 `Thread.sleep()` 轮询。同构场景用 `@ParameterizedTest` 或数据表驱动；单文件软上限约 400 行；一次性验收脚本在里程碑闭环后清理。
