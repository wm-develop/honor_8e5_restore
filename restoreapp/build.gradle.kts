import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.honor.restore"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "wm.honor8e5restore"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        manifestPlaceholders["appLabel"] = "hnubl恢复助手"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    val signingPropertiesFile = File(providers.gradleProperty("PERSONAL_SIGNING_PROPERTIES")
        .orElse(System.getenv("PERSONAL_SIGNING_PROPERTIES") ?: "").get())
    val hasPersonalSigning = signingPropertiesFile.isFile
    if (hasPersonalSigning) {
        val properties = Properties()
        signingPropertiesFile.inputStream().use(properties::load)
        fun credential(name: String): String {
            val value = properties.getProperty(name) ?: error("签名配置缺少 $name：$signingPropertiesFile")
            require(value.isNotBlank()) { "签名配置 $name 不能为空" }
            return value
        }
        val configuredPath = properties.getProperty("storeFile") ?: error("签名配置必须包含 storeFile")
        val storeOverride = System.getenv("PERSONAL_SIGNING_STORE_FILE").orEmpty()
        val candidates = listOfNotNull(
            storeOverride.takeIf { it.isNotBlank() }?.let(::File),
            File(configuredPath),
            File(signingPropertiesFile.parentFile, configuredPath),
        )
        val store = candidates.firstOrNull { it.isFile }
            ?: error("找不到签名密钥库：$configuredPath")
        signingConfigs {
            create("personalRelease") {
                storeFile = store
                storePassword = credential("storePassword")
                keyAlias = credential("keyAlias")
                keyPassword = credential("keyPassword")
            }
        }
    } else {
        logger.warn("未配置个人签名，Release 将使用 debug 签名；请勿将此构建用于正式发布")
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("boolean", "ALLOW_PARTITION_WRITES", "false")
        }
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = if (hasPersonalSigning) signingConfigs.getByName("personalRelease") else signingConfigs.getByName("debug")
            buildConfigField("boolean", "ALLOW_PARTITION_WRITES", "true")
        }
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.useLegacyPackaging = true
    }
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/restoreJni").get().asFile)
}

val sharedCpp = rootProject.file("app/src/main/cpp")
val nativeOutput = layout.buildDirectory.dir("generated/restoreJni/arm64-v8a")

fun registerNativeTool(taskName: String, sourceName: String, outputName: String) {
    val output = nativeOutput.map { it.file(outputName) }
    tasks.register<Exec>(taskName) {
        val source = File(sharedCpp, sourceName)
        inputs.file(source)
        outputs.file(output)
        doFirst {
            val sdk = androidComponents.sdkComponents.sdkDirectory.get().asFile
            val ndk = System.getenv("ANDROID_NDK_HOME")?.let(::File)
                ?: File(sdk, "ndk").listFiles()?.sortedByDescending { it.name }?.firstOrNull {
                    File(it, "toolchains/llvm/prebuilt").isDirectory
                } ?: error("Android NDK is required to compile $outputName")
            val prebuilt = File(ndk, "toolchains/llvm/prebuilt").listFiles()?.firstOrNull { it.isDirectory }
                ?: error("NDK LLVM toolchain is missing")
            val windows = System.getProperty("os.name").startsWith("Windows")
            val clang = File(prebuilt, "bin/aarch64-linux-android28-clang" + if (windows) ".cmd" else "")
            val target = output.get().asFile
            target.parentFile.mkdirs()
            val temp = layout.buildDirectory.dir("nativeTemp").get().asFile.apply { mkdirs() }
            environment("TMP", temp.absolutePath)
            environment("TEMP", temp.absolutePath)
            commandLine(clang.absolutePath, "-std=c11", "-Wall", "-Wextra", "-Werror", "-O2", "-static", source.absolutePath, "-o", target.absolutePath)
        }
    }
    tasks.named("preBuild").configure { dependsOn(taskName) }
}

registerNativeTool("compileRestorePartitionWorker", "partition_worker.c", "librestore_partition_worker.so")
registerNativeTool("compileRestoreGptTool", "gpttool.c", "librestore_gpttool.so")

val stageRestoreBusybox = tasks.register<Copy>("stageRestoreBusybox") {
    from(rootProject.file("app/src/main/jniLibs/arm64-v8a/libbootforge_busybox.so"))
    into(nativeOutput)
    rename { "librestore_busybox.so" }
}
tasks.named("preBuild").configure { dependsOn(stageRestoreBusybox) }

dependencies {
    implementation(project(":restore-core"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")
}
