package database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.worker.WebWorkerSqlDriver
import org.w3c.dom.Worker

actual class DatabaseDriverFactory {
    actual fun createDriver(): SqlDriver {
        // 注意：WebWorkerSqlDriver 需要在 WasmJs 中正确配置 worker
        // 这里假设已经有对应的 worker 逻辑
        return WebWorkerSqlDriver(
            Worker("sqldelight-worker.js")
        )
    }
}
