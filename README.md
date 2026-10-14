# 安大通

安大通是一个立足于安徽大学，由安徽大学学生自发开发的集校园一卡通、电子课表、成绩查询、考试查询等实用功能于一体的App

## 插件平台构建

当前可分发客户端为 `:host`，业务代码位于 `plugins/`，生成独立签名 `.ahup`。
旧 `:app` 保留为迁移参考，默认不加入构建；需要核对旧实现时可显式设置
`-PincludeLegacyApp=true`。组件边界和完整清单见
[插件平台说明](docs/architecture/plugin-platform-plan.md)。

本地签名配置使用被忽略的 `AHUP_SIGNING_CONFIG` 路径；私钥不进入源码或产物目录。
先准备原生领域库，再构建宿主和全部插件：

```powershell
adb devices
python native/build_domains.py --domains campus_card storage --abi arm64-v8a
./gradlew :host:assembleDebug packageAllPlugins testPluginPlatform
```

`.ahup` 输出到 `dist/ahup/`。发布签名 APK 仍需 zipalign、APK 签名与验签；
AIO 仓库提供本地 release 打包 skill，目标模块使用 `host`。
[打包协议](scripts/README_ahup.md)、[本地插件市场](server/plugin-market/README.md)
和 [原生领域构建](native/README.md) 描述了签名、下载及构建要求。
CI 使用临时测试证书构建新宿主和全部组件，其产物不能覆盖正式签名安装。

## 技术栈
安大通 App 采用 MVVM 架构使用全新的 Compose UI框架开发，开发语言以 Kotlin 为主，有少量 Java 代码。

## 软件展示
软件部分界面效果如下：
<div>
    <img src="pic/login_page.png" alt="登录页" width="32%">
    <img src="pic/schedule_page.png" alt="课程表" width="32%">
    <img src="pic/home_page.png" alt="主页" width="32%">
</div>
<div>
    <img src="pic/tools_page.png" alt="工具页" width="32%">
    <img src="pic/grade_page.png" alt="成绩查询" width="32%">
    <img src="pic/exam_page.png" alt="考试查询" width="32%">
</div>

更新了部分内容（2025.9）：

<div>
    <img src="pic/home_page_2025.png" alt="新主页" width="32%">
    <img src="pic/bathroom_charge.png" alt="浴室缴费" width="32%">
    <img src="pic/electricity_charge.png" alt="电控缴费" width="32%">
</div>

## A Historic Moment
An era has ended — **2025-11-01**.
![772f0c1d-bc2a-4d27-a317-9b0ee1ae2a13](https://github.com/user-attachments/assets/07512859-7523-490a-a985-c93554c67a5f)
