import java.util.Properties
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Dasar nama versi dari berkas VERSION di akar repo. Ubah isinya (mis. 0.2.0-beta atau 1.0.0) untuk
// naik ke seri versi berikutnya; nomor build di belakangnya otomatis mulai dari 1 lagi.
fun baseVersionName(): String =
    rootProject.file("VERSION").takeIf { it.isFile }
        ?.readText()?.trim()?.takeIf { it.isNotEmpty() }
        ?: "0.1.0-beta"

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { stream ->
        localProperties.load(stream)
    }
}

// Kunci tanda tangan rilis (pribadi, tidak disimpan di repo). GitHub Actions memasangnya dari Secrets:
//   AMBER_KEYSTORE_FILE     = jalur berkas keystore PKCS12 (alias "amber")
//   AMBER_KEYSTORE_PASSWORD = kata sandi keystore (sekaligus kata sandi kunci)
val releaseKeystoreFile: File? = System.getenv("AMBER_KEYSTORE_FILE")
    ?.takeIf { it.isNotBlank() }
    ?.let { File(it) }
    ?.takeIf { it.isFile }
val releaseKeystorePassword: String? = System.getenv("AMBER_KEYSTORE_PASSWORD")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
val hasReleaseKey = releaseKeystoreFile != null && releaseKeystorePassword != null

// Di repo resmi, APK tidak boleh dibuat tanpa kunci rilis. Tanpa pagar ini, APK bertanda tangan kunci
// debug yang tidak rahasia bisa ikut terbit dan dipalsukan orang lain. Fork dan build lokal tetap
// bisa jalan dengan kunci debug.
val isOfficialRepoBuild = System.getenv("GITHUB_REPOSITORY").equals("ItsVynzzzz/AmberLauncher", ignoreCase = true)
if (isOfficialRepoBuild && !hasReleaseKey) {
    throw GradleException(
        "Kunci tanda tangan rilis belum terpasang. Isi Secrets AMBER_KEYSTORE_BASE64 dan " +
            "AMBER_KEYSTORE_PASSWORD di Settings > Secrets and variables > Actions, " +
            "lalu pastikan berkas workflow terbaru (build-apk.yml) sudah diunggah."
    )
}

// APK 32-bit (armeabi-v7a) dibangun terpisah oleh scripts/build-arm32.sh dengan AMBER_ABI=arm32.
// Tanpa variabel itu (build biasa dan APK 64-bit) semua pengaturan di bawah sama persis seperti
// sebelumnya: hanya arm64-v8a, tanpa build native.
val buildArm32 = System.getenv("AMBER_ABI") == "arm32"

// Mode eksperimen: hanya diset oleh workflow di repo uji coba (AMBER_EXPERIMENT=1). Aplikasinya bisa
// dipasang berdampingan dengan AmberLauncher utama tanpa bentrok: id paket berakhiran ".exp", nama
// "AmberLauncher Exp", folder data dan folder cadangan kontrol sendiri, dan pengecek pembaruan membaca
// repo tempat build berjalan (bukan repo utama). Tanpa variabel itu semuanya sama persis seperti biasa.
val isExperiment = System.getenv("AMBER_EXPERIMENT") == "1"
val experimentRepo = System.getenv("GITHUB_REPOSITORY")
    ?.takeIf { Regex("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+").matches(it) }
    ?: "ItsVynzzzz/AmberLauncher"

android {
    namespace = "com.israadev.nuxlauncher"
    compileSdk = 37

    defaultConfig {
        applicationId = if (isExperiment) "com.itsvynzzzz.amberlauncher.exp" else "com.itsvynzzzz.amberlauncher"
        minSdk = 26
        targetSdk = 34
        // versionCode (dipakai Android untuk menentukan pembaruan) naik sendiri di setiap build GitHub
        // Actions lewat nomor run, jadi JANGAN direset walau nama versi dimulai ulang: Android menolak
        // memasang APK dengan versionCode lebih kecil dari yang sudah terpasang. Build lokal tetap 10.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()?.let { 100 + it } ?: 10
        // Nama versi yang dilihat pengguna: dasarnya dari berkas VERSION di akar repo (mis. 0.1.0-beta).
        // GitHub Actions menambahkan nomor build di belakangnya lewat AMBER_VERSION_NAME (0.1.0-beta.7);
        // build lokal diberi akhiran ".dev" supaya tidak pernah dianggap pembaruan.
        versionName = System.getenv("AMBER_VERSION_NAME")?.trim()?.takeIf { it.isNotEmpty() }
            ?: "${baseVersionName()}.dev"


        // Kunci API CurseForge untuk unduhan di layar Mod. Tidak disimpan di repo: diambil dari variabel
        // lingkungan CURSEFORGE_API_KEY (GitHub Actions Secrets) atau local.properties (curseforge.api.key).
        // Kosong = CurseForge tetap aktif, tetapi pengguna mengisi kuncinya sendiri di aplikasi.
        val curseForgeApiKey = (System.getenv("CURSEFORGE_API_KEY") ?: localProperties.getProperty("curseforge.api.key") ?: "")
            .filter { !it.isWhitespace() && it != '"' && it != '\\' }
        buildConfigField("String", "CURSEFORGE_API_KEY", "\"" + curseForgeApiKey + "\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters.clear()
            abiFilters.add(if (buildArm32) "armeabi-v7a" else "arm64-v8a")
        }

        // Ikon uji coba "Horizon" hanya aktif di APK 64-bit; mesin Horizon tidak ada untuk 32-bit.
        manifestPlaceholders["horizonLauncherEnabled"] = (!buildArm32).toString()

        // Nama aplikasi di layar HP, plus tiga nilai yang dibaca kode (lihat bagian mode eksperimen di atas).
        manifestPlaceholders["appLabel"] = if (isExperiment) "AmberLauncher Exp" else "AmberLauncher"
        buildConfigField("String", "DATA_DIR_NAME", if (isExperiment) "\"nuxlauncher-exp\"" else "\"nuxlauncher\"")
        buildConfigField(
            "String", "BACKUP_DIR",
            if (isExperiment) "\"Documents/LauncherBackup-exp\"" else "\"Documents/LauncherBackup\""
        )
        buildConfigField("String", "UPDATE_REPO", "\"" + (if (isExperiment) experimentRepo else "ItsVynzzzz/AmberLauncher") + "\"")
    }

    if (buildArm32) {
        // Pustaka jembatan (pojavexec, exithook, dst.) untuk 32-bit dibangun dari kode C yang
        // disalin ke src/main/jni oleh scripts/build-arm32.sh. Versi NDK sama dengan proyek hulu.
        ndkVersion = "25.2.9519653"
        externalNativeBuild {
            ndkBuild {
                path = file("src/main/jni/Android.mk")
            }
        }
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("amberRelease") {
                storeFile = releaseKeystoreFile
                storeType = "pkcs12"
                storePassword = releaseKeystorePassword
                keyAlias = "amber"
                keyPassword = releaseKeystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("amberRelease")
            } else {
                // Hanya untuk fork dan build lokal (kunci debug bawaan Android)
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        // Dibutuhkan Android.mk untuk menautkan bytehook; hanya aktif di build 32-bit.
        prefab = buildArm32
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
            if (buildArm32) pickFirsts += listOf("**/libbytehook.so")
        }
    }
}

// ---- Mesin Horizon (Rust) ----
// Horizon: Copyright (c) 2026 Ludovic Marie dan kontributor Horizon, lisensi MIT.
// Port Android: https://github.com/Vasysik/horizon-android
// Pustaka native dan tabel mesinnya TIDAK disimpan di repo (aturan proyek Horizon: tidak ada APK atau
// berkas hasil olahan di Git). Saat build, APK rilis resmi Horizon diunduh dari GitHub dan SHA-256-nya
// dicocokkan dengan nilai yang dikunci di bawah; hanya libhorizon_android.so, engine-data.zip, dan
// berkas lisensinya yang diambil. Bila unduhan gagal atau hash tidak cocok, build berhenti dengan
// pesan yang jelas (tidak ada APK setengah jadi). Untuk naik versi: ganti dua nilai ini dengan versi
// dan SHA-256 dari SHA256SUMS.txt di halaman rilis Horizon.
val horizonVersion = "0.7.5"
val horizonApkSha256 = "83342d2c2827c825b8a3d5d3df77c3082bc67bbd3fafc6121b8d9e9c95a05b6d"
val horizonApkUrl =
    "https://github.com/Vasysik/horizon-android/releases/download/v$horizonVersion/Horizon-Android-$horizonVersion-arm64-v8a.apk"

abstract class FetchHorizonEngine : DefaultTask() {
    @get:Input
    abstract val apkUrl: Property<String>

    @get:Input
    abstract val apkSha256: Property<String>

    /** Folder jniLibs hasil: arm64-v8a/libhorizon_android.so. Diisi otomatis oleh AGP. */
    @get:OutputDirectory
    abstract val jniLibsDir: DirectoryProperty

    /** Folder assets hasil: engine-data.zip dan horizon/LICENSE.txt. Diisi otomatis oleh AGP. */
    @get:OutputDirectory
    abstract val assetsDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val jniOut = jniLibsDir.get().asFile
        val assetsOut = assetsDir.get().asFile
        jniOut.deleteRecursively()
        assetsOut.deleteRecursively()
        jniOut.mkdirs()
        assetsOut.mkdirs()

        val apk = File(temporaryDir, "horizon.apk")
        try {
            downloadWithRetry(apkUrl.get(), apk)
            val actual = sha256Hex(apk)
            if (!actual.equals(apkSha256.get(), ignoreCase = true)) {
                throw GradleException(
                    "APK Horizon yang diunduh tidak cocok dengan SHA-256 yang dikunci di app/build.gradle.kts " +
                        "(seharusnya ${apkSha256.get()}, hasilnya $actual). Build dihentikan demi keamanan."
                )
            }
            ZipFile(apk).use { zip ->
                copyEntry(zip, "lib/arm64-v8a/libhorizon_android.so", File(jniOut, "arm64-v8a/libhorizon_android.so"))
                copyEntry(zip, "assets/engine-data.zip", File(assetsOut, "engine-data.zip"))
                copyEntry(zip, "assets/LICENSE.txt", File(assetsOut, "horizon/LICENSE.txt"))
                copyEntry(zip, "assets/THIRD_PARTY_NOTICES.md", File(assetsOut, "horizon/THIRD_PARTY_NOTICES.md"))
            }
        } finally {
            apk.delete()
        }
        logger.lifecycle("[Amber] Mesin Horizon siap dari rilis resmi (SHA-256 cocok).")
    }

    private fun downloadWithRetry(url: String, target: File) {
        var lastError: Exception? = null
        for (attempt in 1..3) {
            try {
                download(url, target)
                return
            } catch (e: Exception) {
                lastError = e
                logger.warn("[Amber] Unduhan mesin Horizon, percobaan $attempt dari 3 gagal: ${e.message}")
                if (attempt < 3) Thread.sleep(3000L * attempt)
            }
        }
        throw GradleException(
            "Mesin Horizon tidak bisa diunduh dari $url (${lastError?.message}). " +
                "Jalankan ulang build; bila tetap gagal, periksa apakah rilis Horizon itu masih ada.",
            lastError
        )
    }

    /** Unduh dengan pengalihan (GitHub mengalihkan ke server berkas). Hanya HTTPS yang diterima. */
    private fun download(url: String, target: File) {
        var current = URI(url).toURL()
        var hops = 0
        while (true) {
            if (current.protocol != "https") throw IOException("Hanya HTTPS yang diizinkan: $current")
            val conn = current.openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 30_000
                conn.readTimeout = 60_000
                conn.setRequestProperty("User-Agent", "AmberLauncher-build")
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: throw IOException("Pengalihan tanpa tujuan (HTTP $code)")
                    hops++
                    if (hops > 6) throw IOException("Terlalu banyak pengalihan")
                    current = current.toURI().resolve(location).toURL()
                    continue
                }
                if (code != 200) throw IOException("HTTP $code")
                conn.inputStream.use { input ->
                    target.outputStream().use { out -> input.copyTo(out, 128 * 1024) }
                }
                return
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun copyEntry(zip: ZipFile, name: String, dest: File) {
        val entry = zip.getEntry(name)
            ?: throw GradleException("APK Horizon tidak berisi $name. Versi yang dikunci di app/build.gradle.kts mungkin salah.")
        dest.parentFile.mkdirs()
        zip.getInputStream(entry).use { input ->
            dest.outputStream().use { out -> input.copyTo(out, 128 * 1024) }
        }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

// Satu tugas per varian (debug/release). Hasilnya dipasang ke sumber jniLibs dan assets varian itu,
// jadi AGP sendiri mengatur urutan tugas. APK 32-bit tidak memakai mesin ini.
androidComponents {
    onVariants { variant ->
        if (!buildArm32) {
            val fetch = tasks.register<FetchHorizonEngine>(
                "fetchHorizonEngine" + variant.name.replaceFirstChar { it.uppercase() }
            ) {
                apkUrl.set(horizonApkUrl)
                apkSha256.set(horizonApkSha256)
            }
            variant.sources.jniLibs?.addGeneratedSourceDirectory(fetch, FetchHorizonEngine::jniLibsDir)
            variant.sources.assets?.addGeneratedSourceDirectory(fetch, FetchHorizonEngine::assetsDir)
        }
    }
}

// ---- Pemangkasan simbol pustaka native (jaring pengaman ukuran) ----
// Beberapa pustaka bawaan repo (libspirv-cross-c-shared, libshaderconv, libc++_shared) masih membawa
// tabel simbol dan info debug: sekitar 47 MB bila terpasang di HP. AGP membuangnya di langkah
// stripReleaseDebugSymbols, tetapi hanya bila NDK ditemukan; tanpa NDK ia diam-diam mengemas apa adanya.
// Setelah langkah itu, pustaka yang masih berisi .symtab dipangkas dengan llvm-strip (cara yang sama
// seperti AGP). Pustaka yang sudah bersih tidak disentuh. Langkah ini tidak pernah menggagalkan build.
fun findLlvmStrip(): File? {
    val exe = if (System.getProperty("os.name").lowercase().contains("win")) "llvm-strip.exe" else "llvm-strip"
    val roots = mutableListOf<File>()
    for (key in listOf("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "ANDROID_NDK_LATEST_HOME")) {
        System.getenv(key)?.takeIf { it.isNotBlank() }?.let { roots.add(File(it)) }
    }
    for (key in listOf("ANDROID_HOME", "ANDROID_SDK_ROOT")) {
        System.getenv(key)?.takeIf { it.isNotBlank() }?.let { sdk ->
            File(sdk, "ndk").listFiles()?.sortedByDescending { it.name }?.let { roots.addAll(it) }
        }
    }
    for (root in roots) {
        val hosts = File(root, "toolchains/llvm/prebuilt").listFiles() ?: continue
        for (host in hosts) {
            val tool = File(host, "bin/$exe")
            if (tool.isFile) return tool
        }
    }
    for (dir in (System.getenv("PATH") ?: "").split(File.pathSeparator)) {
        if (dir.isBlank()) continue
        val tool = File(dir, exe)
        if (tool.isFile && tool.canExecute()) return tool
    }
    return null
}

// True bila berkas ELF 64-bit little-endian punya bagian tabel simbol (.symtab, tipe SHT_SYMTAB = 2).
fun elfHasSymbols(file: File): Boolean {
    try {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 64) return false
            val header = ByteArray(64)
            raf.readFully(header)
            val isElf = header[0].toInt() == 0x7F && header[1].toInt() == 'E'.code &&
                header[2].toInt() == 'L'.code && header[3].toInt() == 'F'.code
            if (!isElf || header[4].toInt() != 2 || header[5].toInt() != 1) return false
            val head = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val shoff = head.getLong(0x28)
            val shentsize = head.getShort(0x3A).toInt() and 0xFFFF
            val shnum = head.getShort(0x3C).toInt() and 0xFFFF
            if (shoff <= 0L || shentsize < 64 || shnum == 0) return false
            if (shoff + shentsize.toLong() * shnum > raf.length()) return false
            val entry = ByteArray(8)
            for (i in 0 until shnum) {
                raf.seek(shoff + i.toLong() * shentsize)
                raf.readFully(entry)
                // Bidang sh_name (4 byte) lalu sh_type (4 byte)
                if (ByteBuffer.wrap(entry).order(ByteOrder.LITTLE_ENDIAN).getInt(4) == 2) return true
            }
        }
    } catch (_: Throwable) {
    }
    return false
}

tasks.configureEach {
    if (name == "stripReleaseDebugSymbols") {
        doLast {
            try {
                val unstripped = outputs.files.files
                    .flatMap { root -> if (root.isDirectory) root.walkTopDown().toList() else listOf(root) }
                    .filter { it.isFile && it.name.endsWith(".so") && elfHasSymbols(it) }
                if (unstripped.isNotEmpty()) {
                    val tool = findLlvmStrip()
                    if (tool == null) {
                        logger.warn("[Amber] ${unstripped.size} pustaka native masih berisi simbol, tetapi llvm-strip tidak ditemukan (NDK belum terpasang).")
                    } else {
                        var saved = 0L
                        var done = 0
                        for (lib in unstripped) {
                            val before = lib.length()
                            val proc = ProcessBuilder(tool.absolutePath, "--strip-unneeded", lib.absolutePath)
                                .redirectErrorStream(true)
                                .start()
                            proc.inputStream.readBytes()
                            if (proc.waitFor() == 0) {
                                saved += before - lib.length()
                                done++
                            }
                        }
                        logger.lifecycle("[Amber] Simbol dipangkas dari $done dari ${unstripped.size} pustaka native, hemat ${saved / 1024 / 1024} MB.")
                    }
                }
            } catch (e: Throwable) {
                logger.warn("[Amber] Pemangkasan simbol dilewati: ${e.message}")
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.bytehook)
    // Klien proxy resmi mod TouchController (LGPL-3.0). Dipanggil lewat refleksi, lihat TouchControllerBridge.
    implementation(libs.proxy.client.android)
    implementation("androidx.webkit:webkit:1.12.1")
    // Memasang profil dasar (baseline profile) milik aplikasi dan pustaka AndroidX saat pertama dibuka,
    // termasuk APK yang dipasang di luar Play Store, supaya launcher lebih cepat terbuka di HP lemah.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    debugImplementation(libs.androidx.ui.tooling)
}
