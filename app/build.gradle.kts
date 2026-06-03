plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.llama.rpcapp"
    compileSdk = 36
    ndkVersion = "21.4.7075529"

    defaultConfig {
        applicationId = "com.llama.rpcapp"
        minSdk = 16
        targetSdk = 22
        versionCode = 1
        versionName = "1.0"
        multiDexEnabled = true

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        ndk {
            abiFilters.add("armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DCMAKE_MESSAGE_LOG_LEVEL=DEBUG"
                arguments += "-DCMAKE_VERBOSE_MAKEFILE=ON"

                arguments += "-DBUILD_SHARED_LIBS=ON"
                arguments += "-DLLAMA_BUILD_COMMON=ON"
                arguments += "-DLLAMA_OPENSSL=OFF"

                arguments += "-DGGML_NATIVE=OFF"
                arguments += "-DGGML_BACKEND_DL=OFF"
                arguments += "-DGGML_CPU_ALL_VARIANTS=OFF"
                arguments += "-DGGML_LLAMAFILE=OFF"
                
                arguments += "-DLLAMA_BUILD_RPC=ON"
                arguments += "-DGGML_RPC=ON"
                // Link against API 16 Bionic so NDK does not create strtof/log2 PLT to @LIBC.
                arguments += "-DANDROID_PLATFORM=android-16"
                cppFlags += "-std=c++17"
                cppFlags += "-U_FORTIFY_SOURCE"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.multidex:multidex:2.0.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core:1.9.0")
    implementation("com.google.android.material:material:1.9.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.jakewharton.timber:timber:4.7.1")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.journeyapps:zxing-android-embedded:3.3.0") {
        exclude(group = "com.android.support")
    }
    implementation("com.google.zxing:core:3.3.3")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
}
