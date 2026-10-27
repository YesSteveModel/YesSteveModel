# 第一人称验证输入

[generate_fixtures.py](generate_fixtures.py) 生成共享模型声明、标准 protobuf、Bedrock geometry、raw 模型目录、颜色纹理和独立 double 精度矩阵期望。传入输出目录、`protoc` 和当前 geometry schema：

```text
python generate_fixtures.py --output <fixture-dir> --protoc <protoc> --schema <geo_model.proto>
```

`manifest.json` 固定所有输入 hash、模型规模与浮点容限。矩阵采用行主序、列向量；局部根与完整主体链的期望分别计算。动作、失败及宿主轨迹只声明输入与预期，不替代真实提交和绘制边界的观测。

`surfaces` 包含正向与反向完整 cube、部分面和透明面。`surfaces-supported` 使用正向尺寸，供 `FirstPersonLocalResultIntegrationTest` 核对 direct 与 fallback 的顶点、normal 和透明输出。

设置绝对 `YSM_FIRST_PERSON_FIXTURES` 后，用 `test --rerun` 定向运行 `FirstPersonRawFixtureTest` 与 `FirstPersonFixtureIntegrationTest`，确保外部 fixture 变更后执行测试；JNI 测试另需绝对 `YSM_NATIVE_PATH`。前者逐顶点、UV、normal 核对 raw 与 protobuf geometry，后者校验输入 hash，并让全部模型进入真实 bake/Extract。Python 数学 oracle 用 `python -m unittest discover -s <tool-dir> -p 'test_*.py'` 验证。

这些测试证明输入身份和接纳能力，不证明局部渲染、游戏放置、动作提交、输出路径等价或性能。宿主参照、shader/PBR 和分阶段工作量必须由运行证据补齐。
