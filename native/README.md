# Android 原生领域组件

SDK 保留默认 `legacy` 构建供旧客户端使用。正式 AHUP 构建使用 `--no-default-features`，每个 SO 只编译一个领域，不能把旧的 `libahutong_rs.so` 作为必需登录插件载荷。服务端、本地 HTTP 路由、APK/SO 更新和旧校历下载只属于 legacy；宿主和新插件不需要启动旧本地服务器。

| 组件 | Cargo feature | SO | 唯一注册的 JNI bridge |
| --- | --- | --- | --- |
| 账户 | `domain-account` | `libahup_account.so` | `AccountNative` |
| 课表 | `domain-schedule` | `libahup_schedule.so` | `ScheduleNative` |
| 成绩与考试 | `domain-grades` | `libahup_grades.so` | `GradesNative` |
| 校园卡 | `domain-campus-card` | `libahup_campus_card.so` | `CampusCardNative` |
| 旧存储迁移 | `storage-migration` | `libahup_storage.so` | `StorageNative` |

Bridge 的完整包名是 `com.ahu.ahutong.sdk.nativebridge`。`kotlin-templates/` 提供可复制到各插件模块的 Kotlin object；不能把所有 Bridge 编译进宿主或账户必需闭包。R8 必须保留相应 bridge 类名及 native 方法。初始化 SO 应由插件自身类加载器中的 `load(absolutePath)` 执行，以便 `JNI_OnLoad` 能找到插件 DEX 内的 bridge。只在该组件真正执行时加载，不在注册表扫描时加载所有 SO。

## 构建

```powershell
adb devices
python native/build_domains.py --abi arm64-v8a
```

要求 Rust（包括相应 Android target）、`cargo-ndk`、Android NDK。路径优先读取环境或参数，不包含个人绝对路径。可指定 `--ndk`、`--domains account schedule`、`--output`。默认 API 26，ELF 按 16 KiB 页对齐。

产物位于 `native/build/<domain>/lib/<abi>/<name>.so`，汇总为 `native/build/artifacts.json`，包含 feature、ABI、SHA-256、大小和 bridge 名称。构建时检查真实 ELF、唯一 SONAME、JNI_OnLoad，以及其他领域的 bridge / 核心业务字符串不能泄露到该领域。复制到 AHUP 时保持 `lib/<abi>/` 目录结构；任何领域变更都通过新 AHUP 版本交付，不使用旧 SDK 的 SO 自更新。

共享的是 Rust HTTP/Cookie 基础源码，各 SO 独立链接并具有独立 SONAME，不共享可变全局认证对象。账户 SO 只包含 ADWMH/CAS 登录与会话验证；课表、成绩考试和校园卡爬虫、API 及 Parser 实现已经拆到不同源文件并按 feature 编译。

## JNI 契约

所有方法是 `@JvmStatic external fun`，返回 JSON 字符串，应在 IO 线程调用。

| Bridge / 方法 | Kotlin 参数 | JVM 方法签名 |
| --- | --- | --- |
| AccountNative.login | username, password, cookies 三个 String | `(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;` |
| ScheduleNative.getSchedule | cookies: String | `(Ljava/lang/String;)Ljava/lang/String;` |
| GradesNative.getGrade / getExamInfo | cookies: String | `(Ljava/lang/String;)Ljava/lang/String;` |
| CampusCardNative.getBalance / getQrcode / refreshToken | cookies: String | `(Ljava/lang/String;)Ljava/lang/String;` |
| StorageNative.readStrings | snapshotPath, requestsJson 两个 String | `(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;` |

业务调用的 `cookies` 接受旧 SDK 的 cookie_store 原生 JSON 流或 Android 扁平 cookie 数组；每次调用创建独立会话，不自动登录、持久化、启动服务或读取其他账户状态。

```json
{"result": {"原业务JSON":"保持既有结构"}, "cookies":"更新后的cookie_store JSON流"}
```

错误形如 `{"error":"错误说明","cookies":"当前会话快照"}`。请求成功和失败时都可能有服务器更新的 Cookie。Kotlin 账户服务接收结果前必须确认请求的账户 ID / 会话代数仍然有效，账户切换后的旧响应不能覆盖新会话。支付 token 是 `refreshToken` 的 result 字符串，由校园卡插件的会话服务管理。

## 旧 GuiXu 数据迁移

`libahup_storage.so` 没有网络、校园认证或任何业务爬虫，只用于读取旧 GuiXu 二进制格式。它不属于新用户必需插件。旧数据存在时，迁移流程按需安装这个技术组件；迁移完成后的常态存储由 Kotlin 持久层负责。

调用前先停止旧本地服务器/存储写入，把旧存储目录复制到应用私有临时目录，然后将 **快照路径** 交给 `readStrings`。GuiXu 库会以可写 mmap 打开文件，因此不得直接传原目录。原始数据在迁移成功并确认恢复策略之前始终保留。传入需要迁移的全部 box/key 清单（安全存储密文仍按原规则解密，JNI 只读取原字符串）：

```json
[{"box":"session","key":"cookies_json"},{"box":"credentials","key":"account"}]
```

返回 `{"values":[{"box":"session","key":"cookies_json","value":"旧值"},...]}`；不存在的 box/key 返回 null，空字符串保持为空字符串。错误返回 `{"error":"..."}`，不得把读取失败当成空数据完成迁移。调用方完整验证后原子提交 Kotlin 存储和迁移完成标记；失败可重新从原目录建立快照重试。

## 测试

源码单测覆盖独立会话隔离、原生 Cookie 往返、畸形会话不发请求、CAS/学期/考试字段兼容，以及旧 GuiXu 格式和缺失键。可在开发机执行单域 `cargo test --no-default-features --features domain-account`；连接 Android 设备后可以通过 `cargo ndk-test -t arm64-v8a --platform 26 --no-default-features --features domain-account` 运行原生测试。其他 feature 逐个替换测试，不能将所有 domain 合并到一个插件 SO。另需构建一次默认 legacy + server，验证旧 SDK 入口仍能编译。
