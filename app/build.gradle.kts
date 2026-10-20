import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ─── Подпись релизных сборок ────────────────────────────────────────────────
// Ключ НЕ в репозитории. Два источника, оба опциональные:
//  1) keystore.properties в корне проекта (локальная разработка) — в .gitignore;
//  2) env-переменные в CI (ANDROID_KEYSTORE_PATH, ANDROID_KEYSTORE_PASSWORD,
//     ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD) — их удобно хранить в
//     Settings → Secrets and variables → Actions.
// Если источников нет, release собирается под debug-ключом: APK скачивается и
// ставится, но обновление поверх уже установленного потребует удаления (смена
// подписи). Поэтому для настоящих релизов ключ должен быть один и тот же
// бессрочно — см. README, раздел «Релизы и подпись».
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}
val releaseStorePath: String? = keystoreProps.getProperty("storeFile")
    ?: System.getenv("ANDROID_KEYSTORE_PATH")
val releaseStorePassword: String? = keystoreProps.getProperty("storePassword")
    ?: System.getenv("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias: String? = keystoreProps.getProperty("keyAlias")
    ?: System.getenv("ANDROID_KEY_ALIAS")
val releaseKeyPassword: String? = keystoreProps.getProperty("keyPassword")
    ?: System.getenv("ANDROID_KEY_PASSWORD")
val hasReleaseKey = listOf(
    releaseStorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "xyz.imd4n.ebrake"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "xyz.imd4n.ebrake"
        minSdk = 24
        targetSdk = 36
        // versionCode растёт монотонно и никогда не переиспользуется: по нему
        // F-Droid и Google Play отслеживают обновления, по нему же Android
        // понимает «обновление», а не «другое приложение».
        versionCode = 4
        versionName = "0.1.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ffmpeg-kit-next поставляет arm-v7a/arm64/x86/x86_64.
        // Для первой версии оставляем только arm64, чтобы не раздувать APK.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                // file() умеет и абсолютный путь, и относительный от модуля —
                // поэтому keystore.properties с абсолютным путём работает как есть.
                storeFile = file(releaseStorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // Фолбэк на debug-подпись, чтобы `assembleRelease` и CI никогда не
            // падали из-за отсутствия ключа. Для настоящих релизов задай
            // keystore.properties или Secrets — см. блок выше.
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
            optimization {
                // R8: шейкинг кода + ресурсов. Без этого dex в release попадает
                // целиком (~47 МБ распакованных) и ART раздувает его при
                // установке вдвое. AGP 9.3 сам подключает proguard-android-optimize.txt
                // и consumer-правила зависимостей; свои правила — в
                // app/src/main/keepRules/*.keep (не в proguard-rules.pro).
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    lint {
        // В AGP 9.3.1 задача lintVital падает сама (не резолвит lintTool.versionKey),
        // и ломает `assembleRelease` даже без ошибок в коде. Release идёт без неё;
        // статический анализ всё равно доступен вручную: ./gradlew :app:lint
        checkReleaseBuilds = false
        abortOnError = false
        // MissingTranslation и StringFormatMatches включены по умолчанию и
        // являются ошибками: недопереведённая строка и битый плейсхолдер (%3$-s)
        // роняют lint, а не молча печатают мусор. В AGP 9 DSL для warnings/errors
        // удалён, поэтому список проверок не трогаем — он и так нужный.
        checkDependencies = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // Движок перекодирования: локальный AAR из prebuilt/bundle-android-aar-24-maven
    // (собран через ./nix-android.sh -p android-r27d, см. docs/FFMPEG_KIT_NEXT.md).
    implementation("com.arthenica:ffmpeg-kit-next:9.0.0")
    // Плееры превью-сравнения (BOM у Media3 нет — версии в TOML).
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    // Стекло дока: Haze размывает контент под пилюлей внутри той же композиции.
    // Системный FLAG_BLUR_BEHIND на части прошивок (HyperOS) игнорируется,
    // поэтому отдельное окно не давало стекла — только проблемы с позицией.
    implementation(libs.haze)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}