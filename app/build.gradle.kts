import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/**
 * release 签名：优先读 .local/keystore.properties（.local/ 不入库，密钥库也放在里面，storeFile 相对 .local/），
 * 其次读环境变量（给 CI 用，路径相对项目根目录）。都没有时退回 debug 签名，只适合本地侧载测试，不能拿去发布
 */
val releaseSigning: Map<String, String>? = run {
    val file = rootProject.file(".local/keystore.properties")
    if (file.exists()) {
        val props = Properties().apply { file.inputStream().use(::load) }
        val keys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        require(keys.all { !props.getProperty(it).isNullOrBlank() }) { "$file 缺少 ${keys.joinToString()} 中的某一项" }
        return@run keys.associateWith { props.getProperty(it) } +
            ("storeFile" to file.parentFile.resolve(props.getProperty("storeFile")).path)
    }
    val env = mapOf(
        "storeFile" to "LUMY_KEYSTORE_FILE",
        "storePassword" to "LUMY_KEYSTORE_PASSWORD",
        "keyAlias" to "LUMY_KEY_ALIAS",
        "keyPassword" to "LUMY_KEY_PASSWORD",
    ).mapValues { System.getenv(it.value) }
    if (env.values.all { !it.isNullOrBlank() }) env.mapValues { it.value!! } else null
}

if (releaseSigning == null) {
    gradle.taskGraph.whenReady {
        if (allTasks.any { it.project == project && it.name.contains("Release") }) {
            logger.warn("未配置 release 签名（.local/keystore.properties 或 LUMY_* 环境变量），release 包使用 debug 签名，不能用于发布")
        }
    }
}

android {
    namespace = "com.hesier.lumy"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hesier.lumy"
        minSdk = 21
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
        // 电视盒子/投影仪都是 ARM，去掉 x86 的 FFmpeg 库减小体积
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = rootProject.file(releaseSigning.getValue("storeFile"))
                storePassword = releaseSigning.getValue("storePassword")
                keyAlias = releaseSigning.getValue("keyAlias")
                keyPassword = releaseSigning.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        // debug 用单独的包名，和正式版共存：测试包不影响日常使用的数据，正式版签名也只用于正式版
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (releaseSigning != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

/**
 * API 24 以下 zh-HK / zh-MO 不会回退到 zh-TW，会落到简体的 values-zh。
 * 构建时从繁体那份复制出来，免得手工维护几份相同的文件
 */
abstract class TraditionalChineseCopies : DefaultTask() {
    @get:InputFile
    abstract val source: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        for (region in listOf("HK", "MO")) {
            val dir = File(out, "values-zh-r$region").apply { mkdirs() }
            source.get().asFile.copyTo(File(dir, "strings.xml"))
        }
    }
}

val traditionalChineseCopies = tasks.register<TraditionalChineseCopies>("traditionalChineseCopies") {
    source.set(layout.projectDirectory.file("src/main/res/values-zh-rTW/strings.xml"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(traditionalChineseCopies, TraditionalChineseCopies::outputDir)
    }
}

dependencies {
    implementation(project(":net"))
    implementation(project(":server-plex"))
    implementation(project(":server-jellyfin"))

    implementation(libs.coroutines.android)
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.recyclerview)
    implementation(libs.coil)
    implementation(libs.zxing.core)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.hls)
    implementation(libs.media3.ui)
    implementation(libs.media3.okhttp)
    implementation(libs.media3.ffmpeg)
    implementation(libs.ass.media)
}
