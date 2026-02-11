package database

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

class DatabaseDriverFactory(private val context: Context) {
    fun createDriver(): SqlDriver {
        val databasePath = context.getExternalFilesDir(null)?.let { 
            java.io.File(it, "music.db").absolutePath 
        } ?: "music.db"
        return AndroidSqliteDriver(MusicDb.Schema, context, databasePath)
    }
}
