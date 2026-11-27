<!-- SPDX-License-Identifier: CC0-1.0 -->

# 物理资产测试制品

将任一 `.mxc` 复制到空目录即可独立读取。每个文件的通过等级和错误诊断见 [cases.tsv](cases.tsv)，字节 SHA-256、容器版本、preamble、模型身份及各 chunk 布局见 [containers.tsv](containers.tsv)。验证入口和格式义务见[一致性标准](../../conformance.md#物理资产-fixtures)。

三份通过样例分别覆盖无物理字段、可经 JNI 编译的正式导出物和合法边界组合。各形状共用的正尺寸范围由球半径量化为零、胶囊半径低于下界、球半径和盒边长超过上界覆盖。有符号坐标、平移区间和胶囊端点间距各有独立反例。`PhysicsDefinitionValidatorTest` 另行覆盖刚体标量、各图元及两种弹簧参数的逐字段边界。

`reject-unanchored-full-turn-soft.mxc` 的摆动轴全角软限位带正频率，但它是该动态分片通往锚点的唯一边，M2 读取时报告缺少有效锚定。`physics-p4-controls.mxc` 含同样的全角软限位，并由球铰提供有效锚定。[独立拒绝制品](reject-unanchored-full-turn-soft.mxc) · [独立通过制品](physics-p4-controls.mxc)

需要新版本的制品时，将生成结果写到新的目录供审查。生成器调用正式 raw 转换及导出入口创建三份合法样例，再通过正式容器写入器为拒绝用例重写 definition，生成配套索引。导出物包含生成时的导出时间。

```text
./gradlew generatePhysicsConformanceFixtures -PphysicsFixtureOutput=build/physics-fixtures-candidate
```

在 Windows PowerShell 下使用 `.\gradlew.bat` 启动同一任务。正常的 `test` 从资源目录读取已提交的制品。更新样例时一并提交对应的索引和容器摘要。
