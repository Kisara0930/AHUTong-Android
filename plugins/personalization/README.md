# 智能助手插件

`personalization` 为可选自动启动组件，所有 Room 表/迁移、本地频率与 MLP 推理、训练、模型提升、行程与预设建议、样本贡献、质量汇总均属于插件。共享 `data:personalization` 只提供契约和词汇。

宿主提供 `SettingsStore`、`PersonalizationFeatureAccess`、`PluginWorkScheduler`；插件发布 `BehaviorRecorder`、`PresetSuggestions`、`PersonalizationSettings`、`PersonalizationController`。宿主只在启用时转发前台、路由与敏感界面状态；缺少插件时不创建实现或记录数据。建议曝光必须通过 controller 确认，接受建议返回行动 ID，宿主再按可用功能导航。

所有候选与预取经过已安装/已启用能力过滤。付款码内容和缓存属于校园卡组件，助手不保存它们；原有“服务器有效期尚未验证前禁止预测式请求”的规则保留。模型训练与两类远端贡献开关分别保留，未新增遥测字段或默认开启贡献。

后台只提交 `bootstrap_upload` / `quality_upload` 给宿主通用 Worker，不让 WorkManager 反射插件类。停用后取消工作、会话、预取、训练线程，关闭插件数据库；重新启用可读取原 `behavior_prediction_v2.db` 及原密钥/模型目录。迁移原算法与隐私测试到本模块，构建通过 Room KSP 生成插件自己的实现。
