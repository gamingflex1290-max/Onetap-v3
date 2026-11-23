package com.example.onetap

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.system.exitProcess

/** Runs inside the Shizuku process (shell uid), so it can reach restricted folders. */
class FileService : IFileService.Stub() {

    override fun destroy() { exitProcess(0) }

    override fun exists(path: String): Boolean = File(path).exists()

    private inline fun guard(block: () -> Unit): String =
        try { block(); "OK" } catch (e: Throwable) { "ERROR: ${e.message}" }

    private fun target(src: File, dst: String): File {
        val d = File(dst)
        return if (d.isDirectory || dst.endsWith("/")) File(d, src.name) else d
    }

    override fun copy(src: String, dst: String): String = guard {
        val s = File(src)
        require(s.exists()) { "Source nai: $src" }
        val t = target(s, dst)
        t.parentFile?.mkdirs()
        s.copyRecursively(t, overwrite = true)
    }

    override fun move(src: String, dst: String): String = guard {
        val s = File(src)
        require(s.exists()) { "Source nai: $src" }
        val t = target(s, dst)
        t.parentFile?.mkdirs()
        if (!s.renameTo(t)) {
            s.copyRecursively(t, overwrite = true)
            check(s.deleteRecursively()) { "Move: original delete hoy nai" }
        }
    }

    override fun delete(path: String): String = guard {
        val f = File(path)
        if (f.exists()) check(f.deleteRecursively()) { "Delete hoy nai: $path" }
    }

    override fun zip(src: String, zipPath: String): String = guard {
        val s = File(src)
        require(s.exists()) { "Source nai: $src" }
        val z = File(zipPath)
        z.parentFile?.mkdirs()
        val base = s.parentFile ?: s
        ZipOutputStream(z.outputStream().buffered()).use { out ->
            s.walkTopDown().filter { it.isFile }.forEach { f ->
                out.putNextEntry(ZipEntry(f.relativeTo(base).path))
                f.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
    }

    override fun unzip(zipPath: String, dstDir: String): String = guard {
        val d = File(dstDir)
        d.mkdirs()
        val root = d.canonicalPath
        ZipInputStream(File(zipPath).inputStream().buffered()).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                val f = File(d, e.name)
                require(f.canonicalPath.startsWith(root)) { "Bad zip entry" }
                if (e.isDirectory) f.mkdirs() else {
                    f.parentFile?.mkdirs()
                    f.outputStream().use { zin.copyTo(it) }
                }
                e = zin.nextEntry
            }
        }
    }

    /**
     * Extracts entries under [sub] ("" = whole zip) into dstDir, stripping the prefix.
     * If [marker] (e.g. "com.dts.freefiremax/files/") appears in an entry path,
     * everything up to and including it is stripped too.
     */
    override fun unzipSub(zipPath: String, sub: String, marker: String, dstDir: String): String = guard {
        val d = File(dstDir)
        d.mkdirs()
        val root = d.canonicalPath
        var count = 0
        ZipInputStream(File(zipPath).inputStream().buffered()).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                if (e.name.startsWith(sub)) {
                    var rel = e.name.removePrefix(sub)
                    if (marker.isNotEmpty() && rel.contains(marker)) rel = rel.substringAfter(marker)
                    if (rel.isNotEmpty()) {
                        val f = File(d, rel)
                        require(f.canonicalPath.startsWith(root)) { "Bad zip entry" }
                        if (e.isDirectory) f.mkdirs() else {
                            f.parentFile?.mkdirs()
                            f.outputStream().use { zin.copyTo(it) }
                            count++
                        }
                    }
                }
                e = zin.nextEntry
            }
        }
        check(count > 0) { "No files found inside the zip" }
    }

    /** SHA-1 of a file ("" if it does not exist). */
    override fun sha1(path: String): String = try {
        val f = File(path)
        if (!f.isFile) "" else {
            val md = MessageDigest.getInstance("SHA-1")
            f.inputStream().use { ins ->
                val buf = ByteArray(65536)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }
    } catch (e: Throwable) { "" }
}
