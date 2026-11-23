package com.example.onetap

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color as AColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream

private const val GAME_PKG = "com.dts.freefiremax"
private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
private const val BUNDLE_NAME = "optionalab_tailor_002.SnRvXw4SMFqEgZVllj2Y9qAKgW8~3D"
private const val BUNDLE_REL = "contentcache/Optional/android/gameassetbundles/$BUNDLE_NAME"
private const val ZIP_MARKER = "$GAME_PKG/files/"
private const val ASSETS_VER = 1

private fun defaultTarget(root: String) = "$root/Android/data/$GAME_PKG/files"

private fun sha1Of(f: File): String {
    val md = MessageDigest.getInstance("SHA-1")
    f.inputStream().use { ins ->
        val buf = ByteArray(65536)
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

private enum class FileState { UNKNOWN, ACTIVE, INACTIVE, MISSING, OTHER }

class MainActivity : ComponentActivity() {

    // ---- Shizuku / service state ----
    private var service by mutableStateOf<IFileService?>(null)
    private var binding = false
    private var shizukuInstalled by mutableStateOf(false)
    private var shizukuRunning by mutableStateOf(false)
    private var permGranted by mutableStateOf(false)
    private var gameInstalled by mutableStateOf(false)

    // ---- app state ----
    private var busy by mutableStateOf(false)
    private var bn by mutableStateOf(false)
    private var autoMode by mutableStateOf(true)
    private var storage by mutableStateOf("internal") // "internal" or "sd"
    private var sdRootPath by mutableStateOf<String?>(null)
    private var manualPath by mutableStateOf(defaultTarget("/sdcard"))
    private var fileState by mutableStateOf(FileState.UNKNOWN)
    private var result by mutableStateOf<Pair<Boolean, String>?>(null)
    private val logs = mutableStateListOf<String>()
    private var actKind by mutableStateOf("builtin")
    private var deaKind by mutableStateOf("builtin")
    private var actName by mutableStateOf<String?>(null)
    private var deaName by mutableStateOf<String?>(null)
    private var builtinReady by mutableStateOf(false)
    private var actHash: String? = null
    private var deaHash: String? = null

    private val prefs by lazy { getSharedPreferences("p", MODE_PRIVATE) }
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(packageName, FileService::class.java.name))
            .processNameSuffix("fs").version(3)
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, b: IBinder) {
            service = IFileService.Stub.asInterface(b); binding = false
            refresh()
            addLog(tr("Connected to Shizuku", "Shizuku এর সাথে কানেক্ট হয়েছে"))
            if (autoMode) autoDetect(false) else refreshFileState()
        }
        override fun onServiceDisconnected(n: ComponentName) {
            service = null; binding = false; fileState = FileState.UNKNOWN; refresh()
        }
    }

    private val binderListener = Shizuku.OnBinderReceivedListener { refresh() }
    private val deadListener = Shizuku.OnBinderDeadListener {
        service = null; binding = false; fileState = FileState.UNKNOWN; refresh()
    }
    private val permListener = Shizuku.OnRequestPermissionResultListener { _, grant ->
        refresh()
        if (grant == PackageManager.PERMISSION_GRANTED) {
            addLog(tr("Permission granted", "পারমিশন দেওয়া হয়েছে"))
        } else {
            addLog(tr("Permission denied", "পারমিশন দেওয়া হয়নি"))
        }
    }

    private val pickAct = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFile("activate", uri)
    }
    private val pickDea = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFile("deactivate", uri)
    }

    // ---------- helpers ----------
    private fun tr(en: String, bangla: String) = if (bn) bangla else en

    private fun addLog(msg: String) {
        logs.add(0, "${clock.format(Date())}  $msg")
        while (logs.size > 40) logs.removeAt(logs.lastIndex)
    }

    private fun ok(msg: String) { result = true to msg; addLog(msg) }
    private fun fail(msg: String) { result = false to msg; addLog(msg) }

    private fun isInstalled(pkg: String): Boolean = try {
        packageManager.getPackageInfo(pkg, 0); true
    } catch (e: Exception) { false }

    private fun refresh() {
        shizukuInstalled = isInstalled(SHIZUKU_PKG)
        shizukuRunning = try { Shizuku.pingBinder() } catch (e: Throwable) { false }
        permGranted = shizukuRunning && try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) { false }
        gameInstalled = isInstalled(GAME_PKG)
        if (permGranted && service == null && !binding) {
            binding = true
            try { Shizuku.bindUserService(args, conn) } catch (e: Throwable) { binding = false }
        }
    }

    private fun requestPerm() {
        if (!shizukuRunning) { fail(tr("Start Shizuku first.", "আগে Shizuku চালু করুন।")); return }
        val denied = try { Shizuku.shouldShowRequestPermissionRationale() } catch (e: Throwable) { false }
        if (denied) {
            fail(tr(
                "Permission was blocked. In Shizuku, open Authorized apps and allow this app.",
                "পারমিশন ব্লক করা আছে। Shizuku এর Authorized apps এ গিয়ে এই অ্যাপকে Allow করুন।",
            ))
            openShizuku()
        } else {
            Shizuku.requestPermission(1)
        }
    }

    private fun openShizuku() {
        val i = packageManager.getLaunchIntentForPackage(SHIZUKU_PKG)
        if (i != null) { startActivity(i); return }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHIZUKU_PKG")))
        } catch (e: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
        }
    }

    private fun openDevOptions() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) } catch (e2: Exception) { }
        }
    }

    private fun launchGame() {
        val i = packageManager.getLaunchIntentForPackage(GAME_PKG)
        if (i != null) startActivity(i)
        else fail(tr("Free Fire MAX is not installed.", "Free Fire MAX ইন্সটল করা নেই।"))
    }

    private fun packsDir() = getExternalFilesDir("packs")!!.also { it.mkdirs() }

    private fun slotKind(slot: String) = if (slot == "activate") actKind else deaKind

    private fun packFile(slot: String, kind: String) =
        File(packsDir(), if (kind == "builtin") "builtin_$slot.pack" else "$slot.pack")

    private fun loadState() {
        fun kind(slot: String) = prefs.getString("${slot}_kind", null) ?: "builtin"
        actKind = kind("activate"); deaKind = kind("deactivate")
        actName = if (actKind != "builtin") prefs.getString("activate_name", null) else null
        deaName = if (deaKind != "builtin") prefs.getString("deactivate_name", null) else null
    }

    /** Copies the bundled Activate/Deactivate files out of the APK and hashes them. */
    private fun prepareBuiltin() {
        lifecycleScope.launch {
            val okay = withContext(Dispatchers.IO) {
                try {
                    val fresh = prefs.getInt("assets_v", 0) != ASSETS_VER
                    for (slot in listOf("activate", "deactivate")) {
                        val out = File(packsDir(), "builtin_$slot.pack")
                        if (fresh || !out.exists()) {
                            assets.open("$slot.bundle").use { i -> out.outputStream().use { i.copyTo(it) } }
                        }
                    }
                    prefs.edit().putInt("assets_v", ASSETS_VER).apply()
                    actHash = sha1Of(File(packsDir(), "builtin_activate.pack"))
                    deaHash = sha1Of(File(packsDir(), "builtin_deactivate.pack"))
                    true
                } catch (e: Exception) { false }
            }
            builtinReady = okay
            if (!okay) addLog(tr("Built-in files could not be prepared", "বিল্ট-ইন ফাইল রেডি করা যায়নি"))
            refreshFileState()
        }
    }

    /** SD card root (e.g. /storage/1234-ABCD), or null. */
    private fun detectSd(): String? {
        try {
            val dirs = getExternalFilesDirs(null)
            for (i in 1 until dirs.size) {
                val p = dirs[i]?.absolutePath ?: continue
                val idx = p.indexOf("/Android/")
                if (idx > 0) return p.substring(0, idx)
            }
        } catch (e: Exception) { }
        try {
            val rx = Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")
            val f = File("/storage").listFiles()?.firstOrNull { it.name.matches(rx) }
            if (f != null) return f.absolutePath
        } catch (e: Exception) { }
        return null
    }

    private fun rootOf(kind: String): String? = if (kind == "sd") sdRootPath else "/sdcard"

    private fun currentTarget(): String =
        if (autoMode) defaultTarget(rootOf(storage) ?: "/sdcard") else manualPath

    private fun selectStorage(kind: String) {
        if (rootOf(kind) == null) {
            fail(tr("SD card not found. Check that a card is inserted.", "SD card পাওয়া যাচ্ছে না। কার্ড লাগানো আছে কিনা দেখুন।"))
            return
        }
        storage = kind
        prefs.edit().putString("storage", kind).apply()
        refreshFileState()
    }

    @JvmName("applyAutoMode")
    private fun setAutoMode(auto: Boolean) {
        if (!auto && autoMode) manualPath = currentTarget()
        autoMode = auto
        prefs.edit().putBoolean("auto_mode", auto).putString("target", manualPath).apply()
        if (auto) autoDetect(false) else refreshFileState()
    }

    /** Finds which storage actually holds the game folder and selects it. */
    private fun autoDetect(announce: Boolean) {
        val s = service
        if (s == null) {
            if (announce) fail(tr("Connect Shizuku first.", "আগে Shizuku কানেক্ট করুন।"))
            return
        }
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                val order = if (sdRootPath != null) listOf("internal", "sd") else listOf("internal")
                order.firstOrNull { k ->
                    val r = rootOf(k)
                    r != null && try { s.exists("$r/Android/data/$GAME_PKG") } catch (e: Exception) { false }
                }
            }
            if (found != null) {
                if (found != storage) {
                    storage = found
                    prefs.edit().putString("storage", found).apply()
                }
                addLog(tr(
                    "Game folder found on ${if (found == "sd") "SD card" else "device storage"}",
                    "গেম ফোল্ডার পাওয়া গেছে: ${if (found == "sd") "SD card" else "ডিভাইস স্টোরেজ"}",
                ))
            } else if (announce || gameInstalled) {
                addLog(tr(
                    "Game folder not found. Open Free Fire MAX once, then re-scan.",
                    "গেম ফোল্ডার পাওয়া যায়নি। একবার Free Fire MAX ওপেন করে আবার স্ক্যান করুন।",
                ))
            }
            refreshFileState()
        }
    }

    private fun refreshFileState() {
        val s = service
        if (s == null) { fileState = FileState.UNKNOWN; return }
        val path = "${currentTarget().trim().trimEnd('/')}/$BUNDLE_REL"
        lifecycleScope.launch {
            val h = withContext(Dispatchers.IO) { try { s.sha1(path) } catch (e: Exception) { null } }
            fileState = when {
                h == null -> FileState.UNKNOWN
                h.isEmpty() -> FileState.MISSING
                h == actHash -> FileState.ACTIVE
                h == deaHash -> FileState.INACTIVE
                else -> FileState.OTHER
            }
        }
    }

    // ---------- custom packs ----------
    private fun findSlotPrefix(zip: File, slot: String): String? {
        val names = ArrayList<String>()
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            var e = z.nextEntry
            while (e != null) { names.add(e.name); e = z.nextEntry }
        }
        val prefixes = listOf("") + names.filter { it.contains('/') }.map { it.substringBefore('/') + "/" }.distinct()
        for (p in prefixes) {
            for (n in names) {
                if (!n.startsWith(p)) continue
                val seg = n.removePrefix(p).substringBefore('/', "")
                // Folder names like "ACTIVATE 🔸" or "DEACTIVATE🔻" still match.
                if (seg.isNotEmpty() && seg.filter { it.isLetter() }.equals(slot, true)) return "$p$seg/"
            }
        }
        return null
    }

    private fun importFile(slot: String, uri: Uri) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            val msg = withContext(Dispatchers.IO) {
                try {
                    var name = "file"
                    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) name = c.getString(0) }
                    val dir = packsDir()
                    val tmp = File(dir, "tmp.pack")
                    contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                    val isZip = tmp.inputStream().use { it.read() == 0x50 && it.read() == 0x4B }
                    val prefix: String? = if (isZip) {
                        try { findSlotPrefix(tmp, slot) } catch (e: Exception) { null }
                    } else null
                    tmp.copyTo(File(dir, "$slot.pack"), true)
                    tmp.delete()
                    val kind = when {
                        prefix != null -> "zipsub"
                        isZip -> "zip"
                        else -> "file"
                    }
                    prefs.edit()
                        .putString("${slot}_kind", kind)
                        .putString("${slot}_name", name)
                        .putString("${slot}_prefix", prefix ?: "")
                        .apply()
                    true to when (kind) {
                        "zipsub" -> tr("Saved $name (using its '$slot' folder)", "$name সেভ হয়েছে ('$slot' ফোল্ডার ব্যবহার হবে)")
                        "zip" -> tr("Saved $name (whole zip will be extracted)", "$name সেভ হয়েছে (পুরো zip এক্সট্র্যাক্ট হবে)")
                        else -> tr("Saved $name", "$name সেভ হয়েছে")
                    }
                } catch (e: Exception) {
                    false to tr("Upload failed: ${e.message}", "আপলোড হয়নি: ${e.message}")
                }
            }
            busy = false
            loadState()
            if (msg.first) ok(msg.second) else fail(msg.second)
        }
    }

    private fun resetSlot(slot: String) {
        prefs.edit().remove("${slot}_kind").remove("${slot}_name").remove("${slot}_prefix").apply()
        try { File(packsDir(), "$slot.pack").delete() } catch (e: Exception) { }
        loadState()
        addLog(tr("$slot: back to built-in file", "$slot: বিল্ট-ইন ফাইলে ফিরে এসেছে"))
    }

    // ---------- main action ----------
    private fun applyPack(slot: String) {
        val s = service ?: run { fail(tr("Shizuku is not ready yet.", "Shizuku এখনো রেডি না।")); return }
        if (busy) return
        val kind = slotKind(slot)
        val label = slot.replaceFirstChar { it.uppercase() }
        if (kind == "builtin" && !builtinReady) {
            fail(tr("Built-in files are still loading, try again.", "বিল্ট-ইন ফাইল লোড হচ্ছে, আবার চেষ্টা করুন।")); return
        }
        val customName = prefs.getString("${slot}_name", slot) ?: slot
        val prefix = prefs.getString("${slot}_prefix", "") ?: ""
        val pack = packFile(slot, kind).absolutePath
        val t = currentTarget().trim().trimEnd('/')
        val gameDir = "${rootOf(storage) ?: "/sdcard"}/Android/data/$GAME_PKG"
        val expected = if (slot == "activate") actHash else deaHash
        busy = true
        result = null
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                try {
                    val res = when {
                        !s.exists(gameDir) -> "NOT_FOUND"
                        !s.exists(pack) -> "PACK_MISSING"
                        kind == "builtin" -> s.copy(pack, "$t/$BUNDLE_REL")
                        kind == "zipsub" -> s.unzipSub(pack, prefix, ZIP_MARKER, t)
                        kind == "zip" -> s.unzipSub(pack, "", ZIP_MARKER, t)
                        else -> s.copy(pack, if (customName == BUNDLE_NAME) "$t/$BUNDLE_REL" else "$t/$customName")
                    }
                    if (res == "OK" && kind == "builtin" && expected != null &&
                        s.sha1("$t/$BUNDLE_REL") != expected) "VERIFY_FAIL" else res
                } catch (e: Exception) { "ERROR: ${e.message}" }
            }
            busy = false
            when (r) {
                "NOT_FOUND" -> fail(tr(
                    "Free Fire MAX folder not found. Make sure the game is installed and opened once, then tap Re-scan.",
                    "Free Fire MAX এর ফোল্ডার পাওয়া যায়নি। গেম ইন্সটল আছে ও একবার ওপেন করা হয়েছে কিনা দেখে Re-scan চাপুন।",
                ))
                "PACK_MISSING" -> fail(tr("$label file is missing. Upload it again.", "$label ফাইল পাওয়া যাচ্ছে না। আবার আপলোড করুন।"))
                "VERIFY_FAIL" -> fail(tr("Copied, but the file check did not match. Try again.", "কপি হয়েছে কিন্তু ফাইল চেক মেলেনি। আবার চেষ্টা করুন।"))
                "OK" -> ok(tr("$label done ✓  Now open the game.", "$label সম্পন্ন ✓  এখন গেম ওপেন করুন।"))
                else -> fail(r)
            }
            refreshFileState()
        }
    }

    // ---------- lifecycle ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
        )
        sdRootPath = detectSd()
        bn = prefs.getBoolean("bn", false)
        autoMode = prefs.getBoolean("auto_mode", true)
        storage = prefs.getString("storage", "internal") ?: "internal"
        if (storage == "sd" && sdRootPath == null) storage = "internal"
        manualPath = prefs.getString("target", null) ?: defaultTarget(rootOf(storage) ?: "/sdcard")
        loadState()
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addBinderDeadListener(deadListener)
        Shizuku.addRequestPermissionResultListener(permListener)
        setContent { AppTheme { Surface(color = Color.Transparent, contentColor = Pal.text) { Screen() } } }
        prepareBuiltin()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        refreshFileState()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeBinderDeadListener(deadListener)
        Shizuku.removeRequestPermissionResultListener(permListener)
        if (service != null) Shizuku.unbindUserService(args, conn, true)
        super.onDestroy()
    }

    // ================= UI =================

    @Composable
    private fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
        val shape = RoundedCornerShape(20.dp)
        Column(
            modifier.fillMaxWidth()
                .background(Pal.card, shape)
                .border(1.dp, Pal.stroke, shape)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }

    @Composable
    private fun Title(text: String) =
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Pal.text)

    @Composable
    private fun Sub(text: String, color: Color = Pal.sub) =
        Text(text, fontSize = 13.sp, lineHeight = 18.sp, color = color)

    @Composable
    private fun Pill(text: String, color: Color) {
        Box(
            Modifier.background(color.copy(alpha = 0.16f), RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 5.dp)
        ) { Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
    }

    @Composable
    private fun Step(done: Boolean, title: String, hint: String? = null) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(24.dp).background(if (done) Pal.ok else Pal.cardHi, CircleShape)
                    .border(1.dp, if (done) Pal.ok else Pal.stroke, CircleShape),
                contentAlignment = Alignment.Center,
            ) { if (done) Text("✓", color = Color(0xFF04281D), fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (done) Pal.sub else Pal.text)
                if (hint != null && !done) Sub(hint)
            }
        }
    }

    @Composable
    private fun Seg(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
        val shape = RoundedCornerShape(12.dp)
        Box(
            modifier.background(if (selected) Pal.accent else Pal.cardHi, shape)
                .border(1.dp, if (selected) Pal.accent else Pal.stroke, shape)
                .clickable { onClick() }
                .padding(vertical = 11.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (selected) Color.White else Pal.sub)
        }
    }

    @Composable
    private fun BigButton(text: String, sub: String, color: Color, enabled: Boolean, onClick: () -> Unit) {
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = color,
                contentColor = Color(0xFF07101F),
                disabledContainerColor = Pal.cardHi,
                disabledContentColor = Pal.sub,
            ),
            modifier = Modifier.fillMaxWidth().height(68.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(sub, fontSize = 11.sp)
            }
        }
    }

    @Composable
    private fun Ghost(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Pal.stroke),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Pal.text, disabledContentColor = Pal.sub),
            modifier = modifier,
        ) { Text(text, fontSize = 13.sp) }
    }

    @Composable
    private fun Screen() {
        val ready = service != null
        var showMine by remember { mutableStateOf(false) }

        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Pal.bg1, Pal.bg0)))) {
            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // ---- header ----
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("FF MAX Switcher", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Pal.text)
                        Sub(tr("One tap Activate / Deactivate", "এক ট্যাপে Activate / Deactivate"))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Seg("EN", !bn, Modifier.width(46.dp)) { bn = false; prefs.edit().putBoolean("bn", false).apply() }
                        Seg("বাং", bn, Modifier.width(46.dp)) { bn = true; prefs.edit().putBoolean("bn", true).apply() }
                    }
                }

                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Pal.accent, trackColor = Pal.cardHi)

                // ---- setup / permission ----
                if (!ready) {
                    Panel {
                        Title(tr("Setup (one time)", "সেটআপ (একবারই)"))
                        Sub(tr(
                            "Android ${Build.VERSION.RELEASE} blocks apps from touching the game's Android/data folder. This app uses Shizuku to get that access, and only after you allow it.",
                            "Android ${Build.VERSION.RELEASE} এ অ্যাপ সরাসরি গেমের Android/data ফোল্ডারে ঢুকতে পারে না। তাই Shizuku ব্যবহার হয়, আর আপনি Allow না করলে কিছুই হবে না।",
                        ))
                        Step(shizukuInstalled, tr("1. Install Shizuku", "১. Shizuku ইন্সটল করুন"),
                            tr("Get it from Play Store or the official site.", "Play Store বা অফিসিয়াল সাইট থেকে নিন।"))
                        Step(shizukuRunning, tr("2. Start Shizuku", "২. Shizuku চালু করুন"),
                            if (Build.VERSION.SDK_INT >= 30) tr(
                                "Developer options → turn on Wireless debugging → open Shizuku → Pairing → Start. No PC needed.",
                                "Developer options → Wireless debugging অন → Shizuku ওপেন → Pairing → Start। পিসি লাগবে না।",
                            ) else tr(
                                "Android 10 or lower: start Shizuku with a PC (adb) or root.",
                                "Android 10 বা এর নিচে: পিসি (adb) বা root দিয়ে Shizuku চালু করতে হবে।",
                            ))
                        Step(permGranted, tr("3. Allow this app", "৩. এই অ্যাপকে Allow করুন"),
                            tr("Tap the button below and choose Allow.", "নিচের বাটন চেপে Allow দিন।"))
                        Spacer(Modifier.height(2.dp))
                        when {
                            !shizukuInstalled -> Button(
                                onClick = { openShizuku() }, modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                            ) { Text(tr("Install Shizuku", "Shizuku ইন্সটল করুন")) }
                            !shizukuRunning -> {
                                Button(
                                    onClick = { openShizuku() }, modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(14.dp),
                                ) { Text(tr("Open Shizuku", "Shizuku ওপেন করুন")) }
                                if (Build.VERSION.SDK_INT >= 30) Ghost(
                                    tr("Open Developer options", "Developer options খুলুন"), Modifier.fillMaxWidth()
                                ) { openDevOptions() }
                            }
                            !permGranted -> Button(
                                onClick = { requestPerm() }, modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                            ) { Text(tr("Grant permission", "পারমিশন দিন")) }
                            else -> Sub(tr("Connecting…", "কানেক্ট হচ্ছে…"))
                        }
                        Ghost(tr("I did it, check again", "করেছি, আবার চেক করুন"), Modifier.fillMaxWidth()) { refresh() }
                    }
                }

                // ---- status ----
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(if (ready) Pal.ok else Pal.warn, CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (ready) tr("Connected", "কানেক্টেড") else tr("Not connected", "কানেক্ট নেই"),
                            fontWeight = FontWeight.SemiBold, color = Pal.text, modifier = Modifier.weight(1f),
                        )
                        val (txt, col) = when (fileState) {
                            FileState.ACTIVE -> "ACTIVE" to Pal.ok
                            FileState.INACTIVE -> "INACTIVE" to Pal.sub
                            FileState.MISSING -> tr("No file in game", "গেমে ফাইল নেই") to Pal.warn
                            FileState.OTHER -> tr("Other file", "অন্য ফাইল") to Pal.warn
                            FileState.UNKNOWN -> tr("Status unknown", "স্ট্যাটাস জানা নেই") to Pal.sub
                        }
                        Pill(txt, col)
                    }
                    Sub(
                        if (gameInstalled) tr("Free Fire MAX detected ✓", "Free Fire MAX পাওয়া গেছে ✓")
                        else tr("Free Fire MAX not found on this phone", "এই ফোনে Free Fire MAX পাওয়া যায়নি"),
                        if (gameInstalled) Pal.sub else Pal.warn,
                    )
                }

                // ---- actions ----
                BigButton(
                    "Activate",
                    if (actKind == "builtin") tr("Built-in file", "বিল্ট-ইন ফাইল") else (actName ?: "custom"),
                    Pal.ok, ready && !busy,
                ) { applyPack("activate") }
                BigButton(
                    "Deactivate",
                    if (deaKind == "builtin") tr("Built-in file", "বিল্ট-ইন ফাইল") else (deaName ?: "custom"),
                    Pal.bad, ready && !busy,
                ) { applyPack("deactivate") }

                result?.let { (good, msg) ->
                    val c = if (good) Pal.ok else Pal.bad
                    Box(
                        Modifier.fillMaxWidth().background(c.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                            .border(1.dp, c.copy(alpha = 0.5f), RoundedCornerShape(14.dp)).padding(12.dp)
                    ) { Text(msg, color = c, fontSize = 13.sp, lineHeight = 18.sp) }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Ghost(tr("Open game", "গেম ওপেন"), Modifier.weight(1f), gameInstalled) { launchGame() }
                    Ghost(tr("Refresh status", "স্ট্যাটাস রিফ্রেশ"), Modifier.weight(1f), ready) { refresh(); refreshFileState() }
                }

                // ---- target ----
                Panel {
                    Title(tr("Target folder", "টার্গেট ফোল্ডার"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Seg(tr("Auto", "অটো"), autoMode, Modifier.weight(1f)) { setAutoMode(true) }
                        Seg(tr("Manual", "ম্যানুয়াল"), !autoMode, Modifier.weight(1f)) { setAutoMode(false) }
                    }
                    if (autoMode) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Seg(tr("Device storage", "ডিভাইস স্টোরেজ"), storage == "internal", Modifier.weight(1f)) { selectStorage("internal") }
                            Seg("SD card", storage == "sd", Modifier.weight(1f)) { selectStorage("sd") }
                        }
                        Sub(
                            if (sdRootPath != null) tr("SD card found: $sdRootPath", "SD card পাওয়া গেছে: $sdRootPath")
                            else tr("No SD card detected", "SD card পাওয়া যায়নি")
                        )
                        Ghost(tr("Re-scan for the game folder", "গেম ফোল্ডার আবার স্ক্যান করুন"), Modifier.fillMaxWidth(), ready) { autoDetect(true) }
                        SelectionContainer {
                            Text(currentTarget(), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Pal.accent)
                        }
                    } else {
                        OutlinedTextField(
                            value = manualPath,
                            onValueChange = { manualPath = it; prefs.edit().putString("target", it).apply() },
                            label = { Text(tr("Folder path", "ফোল্ডারের পাথ")) },
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Ghost(tr("Reset to auto path", "অটো পাথে ফিরুন"), Modifier.fillMaxWidth()) { setAutoMode(true) }
                    }
                    Sub(tr(
                        "The file goes to  <target>/contentcache/Optional/android/gameassetbundles/",
                        "ফাইল যাবে  <target>/contentcache/Optional/android/gameassetbundles/ এ",
                    ))
                }

                // ---- my own files ----
                Panel {
                    Row(Modifier.fillMaxWidth().clickable { showMine = !showMine }, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Title(tr("Use my own files", "নিজের ফাইল ব্যবহার করুন"))
                            Sub(tr("Optional. Built-in files are used by default.", "ঐচ্ছিক। ডিফল্ট হিসেবে বিল্ট-ইন ফাইল ব্যবহার হয়।"))
                        }
                        Text(if (showMine) "▲" else "▼", color = Pal.sub)
                    }
                    if (showMine) {
                        SlotRow("Activate", actKind, actName, !busy, { pickAct.launch(arrayOf("*/*")) }) { resetSlot("activate") }
                        SlotRow("Deactivate", deaKind, deaName, !busy, { pickDea.launch(arrayOf("*/*")) }) { resetSlot("deactivate") }
                        Sub(tr(
                            "Tip: upload a .zip that has an ACTIVATE / DEACTIVATE folder, or a single file.",
                            "টিপ: ACTIVATE / DEACTIVATE ফোল্ডার আছে এমন .zip, অথবা একটা সিঙ্গেল ফাইল আপলোড করুন।",
                        ))
                    }
                }

                // ---- log ----
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { Title(tr("Activity", "অ্যাক্টিভিটি")) }
                        if (logs.isNotEmpty()) Text(
                            tr("Clear", "মুছুন"), color = Pal.accent, fontSize = 13.sp,
                            modifier = Modifier.clickable { logs.clear() },
                        )
                    }
                    if (logs.isEmpty()) Sub(tr("Nothing yet.", "এখনো কিছু নেই।"))
                    logs.take(8).forEach {
                        Text(it, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Pal.sub, lineHeight = 17.sp)
                    }
                }

                Sub(tr(
                    "Changing game files may break the game's rules or get an account banned. Use at your own risk.",
                    "গেমের ফাইল পরিবর্তন করলে গেমের নিয়ম ভাঙতে পারে বা অ্যাকাউন্ট ব্যান হতে পারে। নিজ দায়িত্বে ব্যবহার করুন।",
                ))
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    @Composable
    private fun SlotRow(
        label: String, kind: String, name: String?, enabled: Boolean,
        onUpload: () -> Unit, onReset: () -> Unit,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontWeight = FontWeight.Medium, color = Pal.text, modifier = Modifier.weight(1f))
                if (kind == "builtin") Pill(tr("Built-in", "বিল্ট-ইন"), Pal.ok) else Pill(tr("Custom", "কাস্টম"), Pal.accent)
            }
            if (kind != "builtin") Sub(name ?: "")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Ghost(tr("Upload file", "ফাইল আপলোড"), Modifier.weight(1f), enabled) { onUpload() }
                Ghost(tr("Use built-in", "বিল্ট-ইন নিন"), Modifier.weight(1f), enabled && kind != "builtin") { onReset() }
            }
        }
    }
}
