package utils

import okio.*

@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
private class WasmFileSystem : FileSystem() {
    private val files = mutableMapOf<String, ByteArray>()

    override fun appendingSink(file: Path, mustExist: Boolean): Sink = sink(file, false)
    override fun atomicMove(source: Path, target: Path) {
        val data = files.remove(source.toString()) ?: throw IOException("Source not found")
        files[target.toString()] = data
    }
    override fun canonicalize(path: Path): Path = path
    override fun createDirectory(dir: Path, mustCreate: Boolean) {}
    override fun createSymlink(source: Path, target: Path) = throw IOException("Not supported")
    override fun delete(path: Path, mustExist: Boolean) {
        files.remove(path.toString())
    }
    override fun list(dir: Path): List<Path> = emptyList()
    override fun listOrNull(dir: Path): List<Path> = emptyList()
    override fun listRecursively(dir: Path, followSymlinks: Boolean): Sequence<Path> = emptySequence()
    override fun metadataOrNull(path: Path): FileMetadata? {
        val data = files[path.toString()] ?: return null
        return FileMetadata(isRegularFile = true, size = data.size.toLong())
    }
    override fun openReadOnly(file: Path): FileHandle = throw IOException("Not supported")
    override fun openReadWrite(file: Path, mustExist: Boolean, mustCreate: Boolean): FileHandle = throw IOException("Not supported")
    override fun sink(file: Path, mustCreate: Boolean): Sink = object : Sink {
        private val buffer = Buffer()
        override fun write(source: Buffer, byteCount: Long) { buffer.write(source, byteCount) }
        override fun flush() {}
        override fun timeout(): Timeout = Timeout.NONE
        override fun close() {
            files[file.toString()] = buffer.readByteArray()
        }
    }
    override fun source(file: Path): Source {
        val data = files[file.toString()] ?: throw IOException("File not found")
        return Buffer().write(data)
    }
}

actual val platformFileSystem: FileSystem = WasmFileSystem()
