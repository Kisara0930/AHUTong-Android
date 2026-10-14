package com.ahu.ahutong.plugin.repository


/**
 * 本地文件的读取与"交给系统查看器打开"。
 *
 * 这两件事都要 Context、FileProvider 与 Intent，属于框架能力；收成接口之后，
 * ViewModel 就只依赖"读到了什么 / 打开成没成功"，可以在 JVM 单测里被 fake 驱动。
 * 生产实现是 :app 的 AndroidRepositoryFileAccess。
 */
interface RepositoryFileAccess {

    /** 读取文件内容（内容 URI 或本地路径）。 */
    fun read(file: DownloadedFile): FileReadOutcome

    /** 用系统查看器打开。失败与"没有可用应用"都作为结果返回，不弹提示。 */
    fun open(file: DownloadedFile): FileOpenOutcome
}

sealed interface FileReadOutcome {

    data class Text(val content: String) : FileReadOutcome

    /** 文件已经不在了（内容 URI 失效或本地文件被删）。 */
    data object Missing : FileReadOutcome

    data class Failed(val message: String?) : FileReadOutcome
}

sealed interface FileOpenOutcome {

    data object Opened : FileOpenOutcome

    data object Missing : FileOpenOutcome

    data object NoViewerApp : FileOpenOutcome

    data class Failed(val message: String?) : FileOpenOutcome
}
