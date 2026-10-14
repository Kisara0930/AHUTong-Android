# AHUTong 官方插件市场

该目录提供独立于 `server/update` 的插件目录、兼容版本查询、依赖解析、更新、不可变下载及本地发布工具。Python 3.8+ 标准库运行 HTTP 服务；发布和验包还需要 OpenSSL 1.1.1+。不安装额外 Python 包，不连接生产服务器。

## 本地使用

服务端和 Android 宿主必须预先信任同一官方签名证书。`--trusted-cert` 接受 X.509 PEM 或 DER，可以重复传入以支持签名证书轮换。签名私钥只由打包工具使用，不放入市场目录或仓库。

```powershell
python server/plugin-market/publish.py verify C:/artifacts/account-1.ahup --trusted-cert C:/keys/official.der
python server/plugin-market/publish.py publish C:/artifacts/account-1.ahup --store C:/ahutong-market --trusted-cert C:/keys/official.der
python server/plugin-market/app.py --store C:/ahutong-market --bind 127.0.0.1 --port 8092 --required account --required appearance
```

当前 Android 宿主的必需组件为 `account`（账户）和 `appearance`（原有基础界面与主题）；多个必需插件重复传入 `--required`。Android 也保留自己的必需插件规则，不能允许市场响应取消宿主的必需组件。

OpenSSL 不在 PATH 时，在子命令前传入 `--openssl C:/path/to/openssl.exe`。Web 服务只有读取和解析接口，没有上传或远程管理接口；发布者需要存储目录的本地写权限。线上使用时，在前置代理终止 HTTPS，并配置连接数、请求速率及访问日志；默认只监听本机。

```powershell
python server/plugin-market/publish.py list --store C:/ahutong-market
python server/plugin-market/publish.py withdraw account 1 --store C:/ahutong-market --reason '登录会话恢复异常'
```

每个 `id/versionCode` 只能发布一次，包括被撤回的版本。修复后必须增加 `versionCode`。撤回会立即从解析结果中排除该版本，下载接口返回 HTTP 410；它不会远程卸载设备上已安装的版本，也不会令客户端静默降级。发生依赖冲突时，服务返回 409，客户端保留现有可运行组合并提示修复。

## AHUP v3 协议

ZIP 根目录包括 `manifest.json` 和二进制 `signature.sig`。签名算法为 Java `SHA256withRSA` / OpenSSL `dgst -sha256`（RSA PKCS#1 v1.5），签名对象是 **manifest.json 的原始 UTF-8 字节**，不进行重新序列化，不额外先做一次 SHA-256。清单的 `files` 精确覆盖其余全部条目；签名保护每个文件的路径、字节数和 SHA-256。包中携带的证书不能成为信任根；如果携带证书，它也是必须列入 `files` 的普通载荷。

```json
{
  "formatVersion": 3,
  "id": "schedule",
  "title": "课表",
  "summary": "课表、课程提醒与桌面课表",
  "author": "AHUTong",
  "versionCode": 1,
  "versionName": "1.0.0",
  "hostApiVersion": 1,
  "runtimeVersion": "1",
  "entryClass": "com.ahu.ahutong.schedule.SchedulePlugin",
  "minSdk": 26,
  "abis": [],
  "required": false,
  "dependencies": [{"id": "account", "minVersion": 1, "maxVersion": 1}],
  "capabilities": ["NETWORK", "PLUGIN_STORAGE"],
  "files": [{"path": "dex/classes.dex", "size": 1234, "sha256": "完整的64位小写十六进制摘要"}]
}
```

关键约束：

- ID 必须符合 `[a-z][a-z0-9_]{1,63}`；所有版本号为正的 32 位整数。
- `hostApiVersion`、`runtimeVersion` 必须与宿主完全一致；`minSdk` 不高于设备 Android API；非空 `abis` 至少匹配设备一个 ABI，空数组代表无 ABI 限制。
- `dependencies` 的 `minVersion` / `maxVersion` 是闭区间 `versionCode`，缺省上界为 2147483647。依赖必须可解析、不可循环。
- 至少包含 `dex/classes.dex`。其他载荷可以包含多 DEX、`resources.apk`、`assets/`、`lib/<abi>/*.so`。清单可包含客户端需要的其他已签名字段。
- 拒绝开发包 `release:false`、无效签名、ZIP 重复路径、穿越路径、符号链接、加密条目、目录条目、未列出文件和哈希/大小不匹配。
- 压缩包不超过 256 MiB，解压总量不超过 256 MiB，单文件不超过 128 MiB，文件数不超过 4096，清单不超过 1 MiB。

## HTTP API

路径以市场服务根地址为基准。目录与详情需要完整兼容参数，避免将不兼容包呈现为可安装。

| 方法与路径 | 说明 |
| --- | --- |
| `GET /healthz` | 进程存活检查 |
| `GET /v1/plugins?hostApiVersion=1&runtimeVersion=1&sdk=35&abis=arm64-v8a` | 每个插件最新的宿主兼容版本与必需插件 ID |
| `GET /v1/plugins/{id}?hostApiVersion=1&runtimeVersion=1&sdk=35&abis=arm64-v8a` | 该插件全部宿主兼容版本，按版本号降序 |
| `POST /v1/resolve` | 为用户选装列表和已有安装解析完整依赖组合 |
| `POST /v1/updates` | 为已安装组件解析更新组合；省略 requested 时检查全部已安装组件 |
| `GET /v1/packages/{id}/{versionCode}/{sha256}.ahup` | 不可变地址下载，支持 HEAD、单区间 Range、ETag 和 If-Range |

多个设备 ABI 使用逗号分隔，如 `abis=arm64-v8a,armeabi-v7a`。`POST` 请求必须为 `Content-Type: application/json`，上限 128 KiB，不支持 chunked 请求体。

解析请求：

```json
{
  "hostApiVersion": 1,
  "runtimeVersion": "1",
  "sdk": 35,
  "abis": ["arm64-v8a"],
  "requested": ["schedule", "grades"],
  "installed": {"account": 1}
}
```

`installed` 必须包含全部仍需保留的插件，以防共享依赖更新破坏未在当前页面选中的功能。解析器尝试较新版本，冲突时回溯选择较旧但兼容的组合；不选择低于已安装版本的包。目录中的“最新”只代表宿主兼容，选择后的最终组合必须调用 resolve，不能逐个下载目录最新版。

返回结构：

```json
{
  "schemaVersion": 1,
  "requested": ["schedule", "grades"],
  "required": ["account"],
  "plugins": [
    {
      "manifest": {"id": "account", "versionCode": 1},
      "download": {
        "url": "/v1/packages/account/1/<sha256>.ahup",
        "sha256": "<完整包的SHA-256>",
        "size": 123456
      },
      "publishedAt": "2026-10-14T00:00:00+00:00",
      "signerCertificateSha256": "<可信证书DER摘要>"
    }
  ],
  "downloads": []
}
```

上述 manifest 省略了其他字段；实际响应保留完整清单。`plugins` 按依赖在前排序，包含完整可运行组合；`downloads` 只包含需要新装/更新的包。客户端下载后仍必须验证压缩包 SHA-256、实际签名和所有载荷，全部通过后按一次安装事务提交。目录的 JSON 元数据与证书摘要仅用于显示和选择，不能代替本机验签。目录和解析响应不可缓存；下载地址虽不可变，仍要求重新验证缓存以响应紧急撤回。

错误响应统一为 `{"error":{"code":"dependency_conflict","message":"..."}}`。常见状态为 400（无效输入）、404（不存在/无兼容版本）、409（依赖冲突或重复发布）、410（已撤回）、416（Range 无效）、422（解算限制）、503（验签器或存储不可用）。插件图最多 128 个节点、搜索最多 20000 次，避免畸形依赖耗尽计算资源。

## 存储、恢复与验证

发布器先复制包到存储所在文件系统，验签与检查同一份快照，随后写入元数据并原子重命名整个版本目录。HTTP 只会看到完整发布的版本。`.writer.lock` 阻止并发发布/撤回；如果进程被强制终止，需要操作者确认没有发布进程，再移除残留锁并重试。发布暂存目录不参与目录扫描。

```text
<store>/packages/<id>/<versionCode>/
  package.ahup
  metadata.json
  withdrawal.json  # 撤回后才有
```

存储目录属于可信管理面，不对外开放静态文件服务，不应被非发布用户写入。备份时包括撤回记录；不能通过删除撤回记录或覆盖版本实现重发。

```powershell
adb devices
python -m unittest discover -s server/plugin-market -v
```

测试使用临时生成的真实 RSA 证书，不依赖生产签名密钥；覆盖清单/载荷篡改、不可信签名、ZIP 路径、重复版本、撤回、兼容矩阵、依赖回溯与冲突、保留已安装功能、更新清单和真实 HTTP Range/ETag。Android 的插件加载与安装回归由客户端测试负责。

## 独立宿主 APK 更新

宿主更新使用 `/v1/host`，不读取 `server/update` 的旧整包/校园 SO 更新源。发布前必须同时通过 Android SDK `aapt2` 的真实包名/版本/minSdk检查、`apksigner` 的官方签名证书检查，以及 APK 内 `assets/plugin-host.json` 的宿主标记。旧 monolith 缺少标记，不能冒充插件宿主发布。

宿主标记示例：

```json
{"schemaVersion":1,"product":"ahutong-plugin-host","packageName":"com.ahu.ahutong","hostApiVersion":1,"runtimeVersion":"1","requiredPluginVersions":{"account":1}}
```

如果标记没有 requiredPluginVersions，通过 CLI 显式指定实际必需版本，例如当前产物的 `--required-plugin account:2 --required-plugin appearance:2`；每个必需版本都必须实际内置于 `assets/bootstrap/<id>.ahup`，发布器再次校验其真实签名、ID、版本和宿主兼容字段。隐藏存储迁移组件只供旧用户初始化，通常不列为所有新安装的必需插件。

```powershell
python server/plugin-market/publish.py publish-host C:/artifacts/ahutong-host-release.apk --store C:/ahutong-market --trusted-cert C:/keys/official.der --signing-config C:/local/config.local.json --required-plugin account:1 --release-notes-file C:/artifacts/host-notes.txt
python server/plugin-market/publish.py withdraw-host 100 --store C:/ahutong-market --reason '宿主兼容性回归'
```

`--signing-config` 复用现有 AIO JKS 配置和 `scripts/AhupSign.java`：密码经 stdin 传递，不出现在进程参数或日志，不导出私钥。可选 `--private-key` 使用已有服务端 PEM 密钥代替 JKS；加密 PEM 可用 `--key-pass-env VARIABLE_NAME`。`--java` 和 `--sdk` 可覆盖本机 JDK/Android SDK 路径。APK 实际签名证书和元数据签名都必须属于 `--trusted-cert` 允许列表。

查询：`GET /v1/host/update?versionCode=99&hostApiVersion=1&runtimeVersion=1&sdk=35`。`sdk` 可省略，但客户端仍须检查 minSdk。同一 hostApiVersion/runtimeVersion 系列只返回更高 versionCode；跨 SDK/runtime 不会猜测兼容性。没有升级返回 `{"schemaVersion":1,"available":false}`。

有升级时返回：

```json
{
  "schemaVersion": 1,
  "available": true,
  "signedMetadata": "<原始UTF-8元数据的Base64>",
  "signature": "<RSA SHA256withRSA签名的Base64>",
  "signerCertificateSha256": "<已配置证书摘要>",
  "metadata": {
    "schemaVersion": 1,
    "product": "ahutong-plugin-host",
    "packageName": "com.ahu.ahutong",
    "versionCode": 100,
    "versionName": "4.0.0",
    "hostApiVersion": 1,
    "runtimeVersion": "1",
    "minSdk": 26,
    "sha256": "<APK完整SHA-256>",
    "size": 123456,
    "url": "/v1/host/packages/100/<sha256>.apk",
    "releaseNotes": "变更说明",
    "requiredPluginVersions": {"account": 1},
    "apkCertificateSha256": ["<APK签名证书摘要>"]
  }
}
```

签名覆盖 `signedMetadata` Base64 解码后的精确字节。`metadata` 只为展示/调试方便，客户端必须以验签后的原文为准，拒绝产品/包名/兼容字段错误、降级或签名错误。下载完成还需核验实际 APK 包名、版本、签名证书、长度、SHA、宿主标记与内置必需插件，再调用系统安装确认。

APK 地址按版本和 SHA 不可变，支持 Range/ETag；撤回后查询排除该版本、下载返回 410。宿主版本存储于 `<store>/hosts/<versionCode>/`，包含 APK、精确元数据、签名、发布记录，原子提交且不覆盖。测试使用真实 RSA 和合法签名 bootstrap，并对宿主身份读取器使用本地 fixture；生产 CLI 不提供跳过 Android APK 验签的选项。
