package com.ahu.ahutong.plugin.repository

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * [RepositoryFileAccess] 的生产实现：内容 URI 读取、FileProvider 授权、交给系统查看器打开。
 *
 * 代码与迁移前逐字一致，只是从 ViewModel 搬到适配器里——ViewModel 因此不再持有 Context，
 * 也就第一次能在 JVM 单测里被 fake 驱动。
 */
class AndroidRepositoryFileAccess(private val context: Context) : RepositoryFileAccess {


    override fun read(file: DownloadedFile): FileReadOutcome {
        return try {
            val uri = file.uri?.let { Uri.parse(it) }
            if (uri != null) {
                val text = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                if (text != null) FileReadOutcome.Text(text)
                else FileReadOutcome.Failed("无法读取本地 Markdown")
            } else {
                val localFile = File(file.localPath)
                if (!localFile.exists()) {
                    FileReadOutcome.Missing
                } else {
                    FileReadOutcome.Text(localFile.readText())
                }
            }
        } catch (e: Exception) {
            Log.e("RepoViewer", "读取失败: " + e.message, e)
            FileReadOutcome.Failed(e.message)
        }
    }

    override fun open(file: DownloadedFile): FileOpenOutcome {
        val uri = file.uri?.let { Uri.parse(it) }
        if (uri != null) return startViewer(uri, file.name)

        val localFile = File(file.localPath)
        if (!localFile.exists()) return FileOpenOutcome.Missing

        val fileUri = try {
            FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                localFile
            )
        } catch (e: Exception) {
            Log.e("RepoViewer", "打开失败: " + e.message, e)
            return FileOpenOutcome.Failed(e.message)
        }
        return startViewer(fileUri, localFile.name)
    }

    private fun startViewer(uri: Uri, fileName: String): FileOpenOutcome {
        return try {
            val mimeType = RepositoryViewModel.getMimeType(fileName)
            val opened = startFileViewer(uri, fileName, mimeType) ||
                (mimeType != "*/*" && startFileViewer(uri, fileName, "*/*"))
            if (opened) FileOpenOutcome.Opened else FileOpenOutcome.NoViewerApp
        } catch (e: Exception) {
            Log.e("RepoViewer", "打开失败: " + e.message, e)
            FileOpenOutcome.Failed(e.message)
        }
    }

    private fun startFileViewer(uri: Uri, fileName: String, mimeType: String): Boolean {
        val clipData = ClipData.newUri(context.contentResolver, fileName, uri)
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addCategory(Intent.CATEGORY_DEFAULT)
            this.clipData = clipData
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooserIntent = Intent.createChooser(viewIntent, "选择打开方式").apply {
            this.clipData = clipData
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        return try {
            context.startActivity(chooserIntent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
