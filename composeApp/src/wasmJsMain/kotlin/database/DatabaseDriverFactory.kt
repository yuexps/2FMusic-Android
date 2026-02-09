package database

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

actual class DatabaseDriverFactory {
    // 内存存储：表名 -> (主键 -> 字段列表)
    private val tables = mutableMapOf<String, MutableMap<String, List<Any?>>>()
    private val listeners = mutableMapOf<String, MutableSet<app.cash.sqldelight.Query.Listener>>()

    actual fun createDriver(): SqlDriver {
        return object : SqlDriver {
            override fun close() {}
            override fun addListener(vararg queryKeys: String, listener: app.cash.sqldelight.Query.Listener) {
                queryKeys.forEach { key ->
                    listeners.getOrPut(key) { mutableSetOf() }.add(listener)
                }
            }
            override fun removeListener(vararg queryKeys: String, listener: app.cash.sqldelight.Query.Listener) {
                queryKeys.forEach { key ->
                    listeners[key]?.remove(listener)
                }
            }
            override fun notifyListeners(vararg queryKeys: String) {
                queryKeys.forEach { key ->
                    listeners[key]?.forEach { it.queryResultsChanged() }
                }
            }

            override fun currentTransaction(): Transacter.Transaction? = null
            
            override fun execute(
                identifier: Int?,
                sql: String,
                parameters: Int,
                binders: (app.cash.sqldelight.db.SqlPreparedStatement.() -> Unit)?
            ): QueryResult<Long> {
                val values = arrayOfNulls<Any?>(parameters)
                val mockPs = object : app.cash.sqldelight.db.SqlPreparedStatement {
                    override fun bindBytes(index: Int, bytes: ByteArray?) { values[index] = bytes }
                    override fun bindDouble(index: Int, double: Double?) { values[index] = double }
                    override fun bindLong(index: Int, long: Long?) { values[index] = long }
                    override fun bindString(index: Int, string: String?) { values[index] = string }
                    override fun bindBoolean(index: Int, boolean: Boolean?) { values[index] = boolean }
                }
                binders?.invoke(mockPs)
                val params = values.toList()

                // debug logging
                utils.Logger.i("MockDB", "EXECUTE: $sql PARAMS: $params")

                var notifiedTable: String? = null
                if (sql.contains("INSERT OR REPLACE INTO SongEntity", ignoreCase = true)) {
                    val id = params[0] as String
                    tables.getOrPut("SongEntity") { mutableMapOf() }[id] = params
                    notifiedTable = "SongEntity"
                } else if (sql.contains("INSERT OR REPLACE INTO PlaylistEntity", ignoreCase = true)) {
                    val id = params[0] as String
                    tables.getOrPut("PlaylistEntity") { mutableMapOf() }[id] = params
                    notifiedTable = "PlaylistEntity"
                } else if (sql.contains("INSERT OR REPLACE INTO PlaylistSong", ignoreCase = true)) {
                    val pid = params[0] as String
                    val sid = params[1] as String
                    tables.getOrPut("PlaylistSong") { mutableMapOf() }["$pid:$sid"] = params
                    notifiedTable = "PlaylistSong"
                } else if (sql.contains("DELETE FROM", ignoreCase = true)) {
                    val table = when {
                        sql.contains("SongEntity", ignoreCase = true) -> "SongEntity"
                        sql.contains("PlaylistEntity", ignoreCase = true) -> "PlaylistEntity"
                        sql.contains("PlaylistSong", ignoreCase = true) -> "PlaylistSong"
                        else -> null
                    }
                    if (table != null) {
                        if (sql.contains("WHERE id =", ignoreCase = true)) {
                            // Single item delete
                            val id = params[0] as String
                            tables[table]?.remove(id)
                        } else if (sql.contains("WHERE playlistId = ? AND songId = ?", ignoreCase = true)) {
                             // Remove song from playlist
                             val pid = params[0] as String
                             val sid = params[1] as String
                             tables[table]?.remove("$pid:$sid")
                        } else if (sql.contains("WHERE", ignoreCase = true)) {
                            // Other WHERE clauses (e.g. playlistId = ?), tough to parse simply, 
                            // but safest is NOT to clear all.
                            // For 'removeAllSongsFromPlaylist' (WHERE playlistId = ?), we should try to match.
                             if (table == "PlaylistSong" && sql.contains("playlistId =", ignoreCase = true)) {
                                 val pid = params[0] as String
                                 val keysToRemove = tables[table]?.keys?.filter { it.startsWith("$pid:") } ?: emptyList()
                                 keysToRemove.forEach { tables[table]?.remove(it) }
                             }
                        } else {
                            // Full delete (no WHERE)
                            tables[table]?.clear()
                        }
                        notifiedTable = table
                    }
                }

                if (notifiedTable != null) {
                    notifyListeners(notifiedTable)
                }

                return QueryResult.Value(1L)
            }

            @Suppress("UNCHECKED_CAST")
            override fun <R> executeQuery(
                identifier: Int?,
                sql: String,
                mapper: (app.cash.sqldelight.db.SqlCursor) -> QueryResult<R>,
                parameters: Int,
                binders: (app.cash.sqldelight.db.SqlPreparedStatement.() -> Unit)?
            ): QueryResult<R> {
                val values = arrayOfNulls<Any?>(parameters)
                val mockPs = object : app.cash.sqldelight.db.SqlPreparedStatement {
                    override fun bindBytes(index: Int, bytes: ByteArray?) { values[index] = bytes }
                    override fun bindDouble(index: Int, double: Double?) { values[index] = double }
                    override fun bindLong(index: Int, long: Long?) { values[index] = long }
                    override fun bindString(index: Int, string: String?) { values[index] = string }
                    override fun bindBoolean(index: Int, boolean: Boolean?) { values[index] = boolean }
                }
                binders?.invoke(mockPs)
                val params = values.toList()

                val tableName = when {
                    sql.contains("getSongsInPlaylist", ignoreCase = true) || sql.contains("INNER JOIN SongEntity", ignoreCase = true) -> "SongEntity" // actually a join, return songs
                    sql.contains("FROM SongEntity", ignoreCase = true) -> "SongEntity"
                    sql.contains("FROM PlaylistEntity", ignoreCase = true) -> "PlaylistEntity"
                    sql.contains("FROM PlaylistSong", ignoreCase = true) -> "PlaylistSong"
                    else -> null
                }
                
                var dataRows = tableName?.let { tables[it]?.values?.toList() } ?: emptyList()
                
                // utils.Logger.i("MockDB", "QUERY: $sql ROWS: ${dataRows.size}")

                // Filter logic
                if (tableName == "SongEntity" && sql.contains("WHERE id = ?", ignoreCase = true)) {
                    val id = params[0] as? String
                    dataRows = dataRows.filter { (it[0] as? String) == id }
                } else if (sql.contains("INNER JOIN SongEntity ON PlaylistSong.songId = SongEntity.id", ignoreCase = true)) {
                    // getSongsInPlaylist: params[0] is playlistId
                    val pid = params[0] as? String
                    // We need to find songIds from PlaylistSong for this pid
                    val playlistSongRows = tables["PlaylistSong"]?.values?.toList() ?: emptyList()
                    val songIds = playlistSongRows.filter { (it[0] as? String) == pid }.map { it[1] as? String }.toSet()
                    
                    // Then filter SongEntity rows
                    dataRows = dataRows.filter { (it[0] as? String) in songIds }
                } else if (tableName == "PlaylistSong" && sql.contains("playlistId = ?", ignoreCase = true)) {
                     // getPlaylistSongIds
                     val pid = params[0] as? String
                     dataRows = dataRows.filter { (it[0] as? String) == pid }
                }

                // 简单的排序模拟
                if (tableName == "SongEntity" && sql.contains("ORDER BY title ASC", ignoreCase = true)) {
                    dataRows = dataRows.sortedBy { it[2] as? String }
                }

                var currentIndex = -1

                return mapper(object : app.cash.sqldelight.db.SqlCursor {
                    override fun next(): QueryResult<Boolean> {
                        currentIndex++
                        return QueryResult.Value(currentIndex < dataRows.size)
                    }
                    override fun getString(index: Int): String? {
                        return dataRows[currentIndex][index] as? String
                    }
                    override fun getLong(index: Int): Long? {
                         return (dataRows[currentIndex][index] as? Number)?.toLong()
                    }
                    override fun getBytes(index: Int): ByteArray? = dataRows[currentIndex][index] as? ByteArray
                    override fun getDouble(index: Int): Double? = (dataRows[currentIndex][index] as? Number)?.toDouble()
                    override fun getBoolean(index: Int): Boolean? = dataRows[currentIndex][index] as? Boolean
                })
            }

            override fun newTransaction(): QueryResult<Transacter.Transaction> {
                return QueryResult.Value(object : Transacter.Transaction() {
                    override val enclosingTransaction: Transacter.Transaction? = null
                    override fun endTransaction(successful: Boolean): QueryResult<Unit> = QueryResult.Unit
                })
            }
        }
    }
}
