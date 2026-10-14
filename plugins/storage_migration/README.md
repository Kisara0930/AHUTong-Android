# 旧 GuiXu 存储迁移

隐藏技术组件 `storage_migration`，没有校园业务、网络权限或账户插件依赖。新版宿主仅在升级遇到旧数据时，从内置 bootstrap AHUP 安装并加载它，必须先完成迁移才能读取账号状态。新安装不安装此组件。

宿主触发条件：`filesDir/rust-sdk` 存在，或者 `filesDir/ahup_migrations/guixu-v1/state.json` 的 phase 尚未达到 `complete`。读取 marker 失败也应显示恢复错误，不能当成新安装。旧 Rust 本地服务不得再启动或写入源目录。

过程及幂等记录：

1. 原子复制旧目录到 `filesDir/ahup_migrations/guixu-v1/snapshot`，核对每个文件 SHA-256；拒绝符号链接、目录、超过 512 MiB 或 4096 文件的源。
2. 原生 `StorageNative.listKeys` 安全枚举当前 GuiXu 格式中的所有字符串键。遇到非字符串类型或损坏索引返回错误，绝不当作空库。读取只针对快照；不修改 GuiXu 子仓。
3. `state.json` 记录 `copy_complete`、源文件清单和 box/key。每个值写到原 `SecureStorage` 的 `box.key`，已有可解密的加密值优先。使用 strict API，不会把解密失败误判为缺失或重置其他凭据。每键加密写入后解密读回一致才记 `committed`，不在 marker 写明文值。
4. 全部键验证后，将未改动的原目录 ZIP 流式加密到 `legacy-guixu.zip.gcm`。归档使用独立 Android Keystore AES-256-GCM 密钥 `ahutong.guixu-migration.archive.v1`；解密验证每文件 SHA、完整文件清单与 GCM tag 后记录 `archived`。
5. 只删除仍符合原清单 SHA 的源文件；清理所有本流程的临时明文快照后记录 `complete`。原始内容保存在已验证的加密归档中，不保留多份明文副本。源变化或清理失败会停止，继续保留已验证归档并可重试。

`LegacyNativeStorage.Reader` 在完整迁移成功后注册。`remove` 对宿主的安全写入/明确删除记录 consumed/tombstone；不会重新导入已删除值。`clearBox` 只记录指定箱的 tombstone。普通运行不会再次启动校园 Rust SDK。

恢复：`copy_complete` 从最后验证过的键继续；`archived` 再验证加密归档和已提交目标后继续清理；`complete` 不因后续用户改密或清空账号而复活备份。损坏快照、源变化、严格解密失败、写入验证失败或归档密钥失效都会抛出明确错误，宿主应留在修复界面。保留原目录与整个迁移目录，不要手动删 marker 或用空值覆盖账号。

`MigrationArchive.restore` 可供明确的恢复操作使用：先验证归档，再还原到一个全新的应用私有目录，拒绝覆盖当前数据。恢复后需重新校验、迁移，不能直接把旧目录替换回运行中的存储。归档依赖本机不可导出的 Keystore 密钥；密钥失效时不能伪称仍可解密。

校验范围：原生索引读取使用真实 GuiXu 二进制 fixture，覆盖存活/删除键与损坏长度。归档 JVM 测试覆盖完整往返、错误密钥、密文破坏和不覆盖恢复目标；Android Keystore 与首次升级流程仍需整机测试。
