plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * The build identity this source declares.
 *
 * A device report from 1.2.2 came back stamped `build 1789174724` — a Unix timestamp —
 * while the source said 14, because the packaging step that produces the installable
 * APK rewrites the `versionCode` literal below before building. These values are
 * compiled into `BuildConfig` so a report can still name the source it came from.
 *
 * Deliberately NOT named `versionCode`: the rewrite searches for that exact lowercase
 * word followed by `=` and digits, and must find the real declaration inside
 * `defaultConfig`, not a helper up here.
 */
val declaredBuildNumber = 18
val declaredVersionName = "1.3.3"

/**
 * Values at or above this are a packaging timestamp rather than a hand-written build
 * number. Used only to tell a stamped build from an un-stamped one.
 */
val stampedVersionFloor = 1_000_000_000

android {
    namespace = "com.rork.rgdsartworkprep"
    compileSdk = 36

    defaultConfig {
        // NEVER change this once the app is in users' hands. Android treats a different
        // applicationId as a different app: an update would install *alongside* the old
        // one, stranding every saved setting, credential, remembered match and folder
        // permission in the original install. The old internal name is kept here on
        // purpose — the public name is set by the launcher label, not by this.
        applicationId = "com.rork.rgdsartworkprep"
        minSdk = 24
        targetSdk = 36

        // Versioning scheme.
        //
        // versionCode must increase by 1 for every build handed to anyone. Android
        // refuses to install a lower code over a higher one, and gives no way to tell
        // two builds apart when the code never moves — which is why this was still 1
        // after ten builds.
        //
        // This was NOT bumped when the background-scan queue shipped, so that build
        // also reported itself as 1.1.0 (11) and its device report could not be told
        // apart from the build before it. Bumped here together with the queue result
        // fixes: from now on, every build handed to anyone moves this by one.
        //
        // versionName is MAJOR.MINOR.PATCH and is what people quote in bug reports:
        //   PATCH  fixes only, nothing a user would notice behaving differently
        //   MINOR  new capability, backwards compatible (this build: scan queue)
        //   MAJOR  a change users need to be told about before they update
        // 1.2.1: the request-level Libretro timeout and the commit-aware job budget.
        // Bumped because this build goes on a device and must be distinguishable from
        // 1.2.0 in a report — which is the whole reason the rule above exists. PATCH,
        // not MINOR: nothing new was added, two behaviours were corrected.
        //
        // 1.2.2: measurement only. The Libretro request path, its timeouts, its retry
        // counts and the queue's scheduling are byte-for-byte the behaviour of 1.2.1 —
        // this build only writes down what each request did. That is exactly why it
        // still gets a new versionCode: it goes on a device, and two reports that
        // cannot be told apart is the failure this rule exists to prevent. The scan
        // engine is `queue-3m` rather than `queue-4` for the same reason, from the
        // other direction: the suffix says "same behaviour, now measured", so a report
        // from this build stays directly comparable with a 1.2.1 one.
        //
        // 1.3.0: MINOR, on both counts that decide it. Four systems that previously
        // reported "lookup is not enabled yet" now scrape, which is new capability a
        // user will notice; and the scan engine changed behaviour rather than being
        // corrected in place — one scanning loop is now guaranteed, index fetches are
        // single-flight, and index parsing is bounded and cancellable. Reports from
        // this build are NOT comparable with a queue-3 one, which is why the engine
        // revision moves to `queue-4` rather than taking another suffix.
        //
        // 1.3.1: PATCH. Nothing new was added — the four systems arrived in 1.3.0 and
        // are untouched — but three ROMs that failed on the device now succeed. Reading
        // the archive index no longer runs a regular expression over several megabytes
        // or normalises a name three times per entry, and a device that cannot finish
        // processing a reply says so once instead of proving it three times. The scan
        // engine moves to `queue-5` because those are behaviour changes: a report from
        // this build is not comparable with a `queue-4` one.
        //
        // 1.3.2: PATCH, and presentation only. A row whose automatic match came back
        // empty was labelled "Not found" in red while still being one tap from being
        // resolved by hand — so red stopped meaning "something went wrong" and started
        // meaning "look here sometimes". That state is now amber "Choose artwork", and
        // "Not found" is left to mean what it says: a search ran and found nothing.
        // The scan engine stays `queue-5`: no scheduling, provider order, matching or
        // request behaviour changed, so a report from this build is directly comparable
        // with a 1.3.1 one — aside from naming the new state where it appears.
        //
        // 1.3.3: PATCH, and a correctness fix. Choosing artwork by hand for one ROM
        // could change the artwork of others: remembered matches were keyed by the
        // ROM's checksum alone, so any two files holding identical bytes shared one
        // cache entry and therefore one identification. On the reporting device all
        // five test ROMs shared bytes, so a cover picked for Final Fantasy VII was
        // read back as every other game's own match and written into each of their
        // correctly named files. The key is now the library entry — system, filename,
        // size, then checksum — so two entries can never collide. Entries written
        // under the old key are discarded on first launch rather than migrated: that
        // key does not record which file it was written for.
        //
        // The scan engine stays `queue-5`. Scheduling, provider order, matching
        // thresholds and request behaviour are untouched, so a report from this build
        // is directly comparable with a 1.3.2 one — with one expected difference on
        // the first scan after updating, where ROMs whose remembered match was
        // discarded are identified again instead of being served from the cache.
        //
        // The next line MUST stay a plain integer literal. The packaging step that
        // builds the installable APK rewrites this file first, replacing the first
        // `versionCode = <digits>` it finds with a build timestamp — which is what
        // keeps version codes climbing across installs. The first 1.3.0 attempt wrote
        // `versionCode = sourceVersionCode` here; the rewrite matches only digits, so
        // it silently changed nothing and the APK shipped as 15 while the device
        // already had the stamped 1789174724 installed. Android will not install a
        // lower version code over a higher one, so the RG DS rejected the package.
        // A reference compiles and tests green — it fails only on the device.
        versionCode = 18
        versionName = declaredVersionName

        // Warn — never fail — when the literal above and declaredBuildNumber disagree.
        //
        // This was a `check()` that aborted configuration, and that was a mistake. It
        // assumed the packaging step only ever writes a Unix timestamp, so any value
        // below stampedVersionFloor had to be developer error. It is not: packaging
        // has two rewrite paths, and the App Bundle one stamps a real Play version
        // code — a small number, legitimately far under that floor. The check then
        // failed the build during configuration, before a single file compiled, on a
        // rewrite that was doing exactly what it was supposed to do.
        //
        // A build file must not fail a build over a value an external tool owns and is
        // entitled to set. The invariant that actually matters — that the line stays a
        // plain integer literal the rewrite can find — is enforced by the rewrite
        // matching it, and SOURCE_VERSION_CODE below records what the source declared,
        // so a device report still names its origin whatever gets stamped.
        val configuredVersionCode = versionCode ?: 0
        if (configuredVersionCode != declaredBuildNumber &&
            configuredVersionCode < stampedVersionFloor
        ) {
            logger.warn(
                "versionCode literal ($configuredVersionCode) disagrees with " +
                    "declaredBuildNumber ($declaredBuildNumber). Expected when packaging " +
                    "stamps a release version code; update both together if not."
            )
        }

        // Compiled from the source above, so a report names the code it came from even
        // when packaging stamps a different version code into the manifest.
        buildConfigField("int", "SOURCE_VERSION_CODE", "$declaredBuildNumber")
        buildConfigField("String", "SOURCE_VERSION_NAME", "\"$declaredVersionName\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.android)
    // The libretro archive is fetched through OkHttp rather than the Android engine:
    // only OkHttp can bound a whole call and close a socket that is blocked mid-read.
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.koin.androidx.compose)
    debugImplementation(libs.androidx.ui.tooling)
    // SystemCatalog detection is pure Kotlin with no Android dependencies, so the
    // folder/extension rules can be asserted on the JVM without a device.
    testImplementation(libs.junit)
}
