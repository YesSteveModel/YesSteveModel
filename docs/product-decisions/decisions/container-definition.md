# container-definition

- Requirement: [REQ.portable-model-artifact](../requirements/req-portable-model-artifact.md#reqportable-model-artifact)
- Select: 单文件制品与完整模型定义
- Landing:
  - [standard](../../standards/asset-container.md#总体布局)
  - [standard](../../standards/model-schema/README.md#逻辑组成)

## DD.single-file-model-container

- Claim: 模型以一个容器文件携带清单及其组织的资产。
- Rationale: 玩家分发单个文件比维护一组有相对引用的松散文件更直接，也更不容易漏掉模型所需内容；清单把分发单元与模型定义的边界对齐。

### BC.container-carries-model-definition

- Claim: 容器保存几何体、适配的动画、控制器、纹理、i18n 字符串、脚本、声音，以及清单引用、配置和元数据，不要求接收者同时取得作者的工作目录。
