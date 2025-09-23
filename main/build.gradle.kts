import com.android.build.gradle.api.ApplicationVariant
import org.gradle.api.Action
import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("checkstyle")
}

android {
    namespace = "de.blinkt.openvpn"
    compileSdk = 35
    ndkVersion = "28.0.13004108"

    defaultConfig {
        applicationId = "uk.co.aiovpn.app"   // AIO VPN package id
        minSdk = 21
        targetSdk = 35
        versionCode = 216
        versionName = "0.7.61"

        externalNativeBuild {
            cmake { /* arguments += "-DCMAKE_VERBOSE_MAKEFILE=1" */ }
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    // Keep splits off to produce a single universal APK in CI
    splits {
        abi {
            isEnable = false
            isUniversalApk = true
        }
        density { isEnable = false }
    }

    // If you build App Bundles later, don’t split by language
    bundle {
        language { enableSplit = false }
    }

    externalNativeBuild {
        cmake {
            path = File("${projectDir}/src/main/cpp/CMakeLists.txt")
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets", "build/ovpnassets")
        }
        create("ui") { }
        create("skeleton") { }
        getByName("debug") { }
        getByName("release") { }
    }

    signingConfigs {
        create("release") {
            val keystoreFile: String? by project
            storeFile = keystoreFile?.let { file(it) }

            val keystorePassword: String? by project
            storePassword = keystorePassword

            val keystoreAliasPassword: String? by project
            keyPassword = keystoreAliasPassword

            val keystoreAlias: String? by project
            keyAlias = keystoreAlias

            enableV1Signing = true
            enableV2Signing = true
        }

        create("releaseOvpn2") {
            val keystoreO2File: String? by project
            storeFile = keystoreO2File?.let { file(it) }

            val keystoreO2Password: String? by project
            storePassword = keystoreO2Password

            val keystoreO2AliasPassword: String? by project
            keyPassword = keystoreO2AliasPassword

            val keystoreO2Alias: String? by project
            keyAlias = keystoreO2Alias

            enableV1Signing = true
            enableV2Signing = true
        }
    }

    lint {
        enable += setOf(
            "BackButton", "EasterEgg", "StopShip",
            "IconExpectedSize", "GradleDynamicVersion", "NewerVersionAvailable"
        )
        checkOnly += setOf("ImpliedQuantity", "MissingQuantity")
        disable += setOf("MissingTranslation", "UnsafeNativeCodeLocation")
    }

    flavorDimensions += listOf("implementation", "ovpnimpl")

    productFlavors {
        create("ui") {
            dimension = "implementation"
        }
        create("skeleton") {
            dimension = "implementation"
        }
        create("ovpn23") {
            dimension = "ovpnimpl"
            buildConfigField("boolean", "openvpn3", "true")
        }
        create("ovpn2") {
            dimension = "ovpnimpl"
            versionNameSuffix = "-o2"
            buildConfigField("boolean", "openvpn3", "false")
        }
    }

    // Skip building the skeleton flavor (keeps CI fast & avoids its errors)
    @Suppress("DEPRECATION")
    variantFilter {
        if (flavors.any { it.name == "skeleton" }) {
            ignore = true
        }
    }

    buildTypes {
        getByName("release") {
            if (project.hasProperty("icsopenvpnDebugSign")) {
                logger.warn("property icsopenvpnDebugSign set, using debug signing for release")
                signingConfig = android.signingConfigs.getByName("debug")
            } else {
                productFlavors["ovpn23"].signingConfig = signingConfigs.getByName("release")
                productFlavors["ovpn2"].signingConfig = signingConfigs.getByName("releaseOvpn2")
            }
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            // Prevent META-INF duplicate clashes during packaging
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/LICENSE*",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/*.kotlin_module",
                "META-INF/kotlin-tooling-metadata.json",
                "META-INF/*.version",
                "META-INF/gradle/incremental.annotation.processors"
            )
        }
    }

    bundle {
        codeTransparency {
            signing {
                val keystoreTPFile: String? by project
                storeFile = keystoreTPFile?.let { file(it) }

                val keystoreTPPassword: String? by project
                storePassword = keystoreTPPassword

                val keystoreTPAliasPassword: String? by project
                keyPassword = keystoreTPAliasPassword

                val keystoreTPAlias: String? by project
                keyAlias = keystoreTPAlias

                if (keystoreTPFile.isNullOrEmpty()) println("keystoreTPFile not set, disabling transparency signing")
                if (keystoreTPPassword.isNullOrEmpty()) println("keystoreTPPassword not set, disabling transparency signing")
                if (keystoreTPAliasPassword.isNullOrEmpty()) println("keystoreTPAliasPassword not set, disabling transparency signing")
                if (keystoreTPAlias.isNullOrEmpty()) println("keyAlias not set, disabling transparency signing")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

/* ----- SWIG / OpenVPN3 codegen integration (unchanged logic) ----- */
var swigcmd = "swig"
if (file("/opt/homebrew/bin/swig").exists())
    swigcmd = "/opt/homebrew/bin/swig"
else if (file("/usr/local/bin/swig").exists())
    swigcmd = "/usr/local/bin/swig"

fun registerGenTask(variantName: String, variantDirName: String): File {
    val baseDir = File(buildDir, "generated/source/ovpn3swig/${variantDirName}")
    val genDir = File(baseDir, "net/openvpn/ovpn3")

    tasks.register<Exec>("generateOpenVPN3Swig${variantName}") {
        doFirst { mkdir(genDir) }
        commandLine(
            listOf(
                swigcmd, "-outdir", genDir, "-outcurrentdir", "-c++", "-java",
                "-package", "net.openvpn.ovpn3",
                "-Isrc/main/cpp/openvpn3/client", "-Isrc/main/cpp/openvpn3/",
                "-DOPENVPN_PLATFORM_ANDROID",
                "-o", "${genDir}/ovpncli_wrap.cxx", "-oh", "${genDir}/ovpncli_wrap.h",
                "src/main/cpp/openvpn3/client/ovpncli.i"
            )
        )
        inputs.files("src/main/cpp/openvpn3/client/ovpncli.i")
        outputs.dir(genDir)
    }
    return baseDir
}

@Suppress("DEPRECATION")
android.applicationVariants.all(object : Action<ApplicationVariant> {
    override fun execute(variant: ApplicationVariant) {
        val sourceDir = registerGenTask(variant.name, variant.baseName.replace("-", "/"))
        val task = tasks.named("generateOpenVPN3Swig${variant.name}").get()
        variant.registerJavaGeneratingTask(task, sourceDir)
    }
})

dependencies {
    // Networking (panel login + HTTP)
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")

    // AndroidX / UI
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.webkit)
    implementation(libs.android.view.material)

    // Kotlin stdlib (from versions catalog)
    implementation(libs.kotlin)

    // Charts & extra HTTP (from versions catalog if used)
    implementation(libs.mpandroidchart)
    implementation(libs.square.okhttp)

    // Tests
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin)
    testImplementation(libs.mockito.core)
    testImplementation(libs.robolectric)
}
