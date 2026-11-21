# 第一人称验证输入

[generate_fixtures.py](generate_fixtures.py) 用于生成同源的共享模型声明、标准 protobuf、Bedrock 几何、raw 模型目录、颜色纹理，以及独立的双精度矩阵期望结果。执行时传入输出目录、`protoc` 路径和当前几何 Schema：

```text
python generate_fixtures.py --output <fixture-dir> --protoc <protoc> --schema <geo_model.proto>
```

生成的 `manifest.json` 固定记录所有输入的 hash、模型规模和浮点容限。矩阵采用行主序与列向量约定，并分别计算局部根骨骼与完整主体链的期望结果。脚本中的动作、失败及宿主轨迹只用于声明输入与预期，不能替代真实提交与绘制边界的实机观测。

`surfaces` 包含正向与反向完整 cube、部分面和透明面；`surfaces-supported` 则只使用正向尺寸，供 `FirstPersonLocalResultIntegrationTest` 核对 direct 与 fallback 路径的顶点、法线和透明输出。

配置绝对路径的 `YSM_FIRST_PERSON_FIXTURES` 后，运行 `test --rerun` 定向执行 `FirstPersonRawFixtureTest` 与 `FirstPersonFixtureIntegrationTest`，确保外部 fixture 更新后重新跑测；涉及 JNI 的测试还需设置绝对路径的 `YSM_NATIVE_PATH`。前者逐顶点、UV 和法线核对 raw 与 protobuf 几何的一致性；后者校验输入 hash，并将全部模型送入真实的 bake 与 extract 流程。Python 数学 oracle 通过 `python -m unittest discover -s <tool-dir> -p 'test_*.py'` 验证。

这组测试只证明输入身份一致以及模型能被底层正确接纳，不证明局部渲染效果、游戏内放置姿态、动作提交、两条输出路径等价或性能表现；宿主参照、光影与 PBR 表现以及分阶段开销必须由运行态证据补齐。
