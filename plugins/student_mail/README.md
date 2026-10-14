# 学生邮箱插件

包含原生收发、回复/转发、草稿、文件夹、搜索、联系人、附件上传下载、邮件正文图片与隔离网页版。发布/投递/移动类写入保留一次性请求和“结果未确认”状态，不能因网络失败自动重发。

校园会话仅通过账户插件 `CampusSessionService` 获取受控 client/续期。邮箱 CookieJar 与校园 CookieJar 始终分离。账号代数、退出、停用时使内存会话与网页租约失效；普通停用保留 `student_mail_session_v1` 的加密记录，重新启用按当前账号验证后恢复。

宿主声明通用 `:student_mail` ComponentActivity 代理以及主进程 `${applicationId}.student_mail_session` Provider。宿主在任何 WebView 创建前设置独立数据目录；插件 Activity controller 保留原 HTTPS/主机限制、证书失败拒绝、文件选择/保存、缩放与关闭清理。Intent 只带随机租约，不带 SID/Cookie；主进程 Provider 限本 UID，每次租约校验都检查当前会话，网页版每 2 秒再次验证。

源文件均在插件 DEX 中；宿主不保留邮箱 Activity/Provider 的业务实现。原邮箱协议、正文、草稿、上传下载、会话与 viewport 契约测试随模块迁移。
