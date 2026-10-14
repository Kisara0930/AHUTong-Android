package com.ahu.ahutong.personalization.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object BehaviorDatabaseFiles {
    const val LEGACY_V1 = "behavior_prediction.db"
    const val CURRENT_V2 = "behavior_prediction_v2.db"
}

internal object BehaviorDatabaseFactory {
    fun open(context: Context): BehaviorDatabase {
        BehaviorDatabaseBootstrap.prepare(context)
        preserveFutureDatabase(context)
        val database = runCatching { buildAndOpen(context) }
            .getOrElse { firstFailure ->
                preserveDatabase(context, "recovery")
                runCatching { buildAndOpen(context) }
                    .getOrElse { retryFailure ->
                        retryFailure.addSuppressed(firstFailure)
                        throw retryFailure
                    }
            }
        return database
    }

    private fun preserveFutureDatabase(context: Context) {
        val database = context.getDatabasePath(BehaviorDatabaseFiles.CURRENT_V2)
        if (!database.exists()) return
        val version = runCatching { readVersion(database.absolutePath) }.getOrNull() ?: return
        if (version > BEHAVIOR_DATABASE_VERSION) preserveDatabase(context, "downgrade-v$version")
    }

    private fun preserveDatabase(context: Context, reason: String) {
        val database = context.getDatabasePath(BehaviorDatabaseFiles.CURRENT_V2)
        if (!database.exists()) return
        val suffix = ".$reason-${System.currentTimeMillis()}"
        listOf("", "-wal", "-shm", "-journal").forEach { extension ->
            val source = database.resolveSibling(database.name + extension)
            if (source.exists()) {
                val target = database.resolveSibling(database.name + suffix + extension)
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun readVersion(path: String): Int {
        val database = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE)
        return try {
            database.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { cursor ->
                while (cursor.moveToNext()) Unit
            }
            database.version
        } finally {
            database.close()
        }
    }

    private fun buildAndOpen(context: Context): BehaviorDatabase {
        val database = Room.databaseBuilder(
            context,
            BehaviorDatabase::class.java,
            BehaviorDatabaseFiles.CURRENT_V2
        )
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(
                BehaviorDatabaseMigrations.MIGRATION_1_2,
                BehaviorDatabaseMigrations.MIGRATION_2_3,
                BehaviorDatabaseMigrations.MIGRATION_3_4,
                BehaviorDatabaseMigrations.MIGRATION_4_5
            )
            .build()
        return try {
            database.openHelper.writableDatabase
            database
        } catch (failure: Throwable) {
            database.close()
            throw failure
        }
    }
}

internal enum class LegacyBootstrap {
    NONE,
    COPIED_V1,
    COPIED_V2,
    SKIPPED_UNSUPPORTED
}

internal object BehaviorDatabaseBootstrap {
    fun prepare(context: Context): LegacyBootstrap {
        val current = context.getDatabasePath(BehaviorDatabaseFiles.CURRENT_V2)
        if (current.exists()) return LegacyBootstrap.NONE
        val legacy = context.getDatabasePath(BehaviorDatabaseFiles.LEGACY_V1)
        if (!legacy.exists()) return LegacyBootstrap.NONE

        val version = runCatching { checkpointAndReadVersion(legacy.absolutePath) }
            .getOrElse {
                return LegacyBootstrap.SKIPPED_UNSUPPORTED
            }
        if (version !in 1..2) {
            return LegacyBootstrap.SKIPPED_UNSUPPORTED
        }

        current.parentFile?.mkdirs()
        val temporary = current.resolveSibling("${current.name}.importing")
        return runCatching {
            Files.deleteIfExists(temporary.toPath())
            Files.copy(legacy.toPath(), temporary.toPath(), StandardCopyOption.REPLACE_EXISTING)
            try {
                Files.move(
                    temporary.toPath(),
                    current.toPath(),
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    current.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
            if (version == 1) LegacyBootstrap.COPIED_V1 else LegacyBootstrap.COPIED_V2
        }.getOrElse {
            Files.deleteIfExists(temporary.toPath())
            context.deleteDatabase(BehaviorDatabaseFiles.CURRENT_V2)
            LegacyBootstrap.SKIPPED_UNSUPPORTED
        }
    }

    private fun checkpointAndReadVersion(path: String): Int {
        val database = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE)
        return try {
            database.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { cursor ->
                while (cursor.moveToNext()) Unit
            }
            database.rawQuery("PRAGMA user_version", null).use { cursor ->
                check(cursor.moveToFirst()) { "missing SQLite user_version" }
                cursor.getInt(0)
            }
        } finally {
            database.close()
        }
    }
}

class BehaviorDatabaseCompatibilityStore constructor(
    private val context: Context
) {
    fun clearLegacyLearningDatabase() {
        context.deleteDatabase(BehaviorDatabaseFiles.LEGACY_V1)
    }
}
