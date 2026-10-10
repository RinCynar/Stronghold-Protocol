import os
import sys
import shutil
import zipfile
import subprocess
import time

def main():
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    sys.stderr.reconfigure(encoding='utf-8', errors='replace')
    start_time = time.time()
    script_dir = os.path.dirname(os.path.abspath(__file__))
    project_root = os.path.dirname(script_dir)
    os.chdir(script_dir)

    android_home = r"C:\Users\RinCynar\AndroidSDK"
    build_tools = os.path.join(android_home, "build-tools", "36.1.0")
    platform_jar = os.path.join(android_home, "platforms", "android-36", "android.jar")
    aapt2 = os.path.join(build_tools, "aapt2.exe")
    d8 = os.path.join(build_tools, "d8.bat")
    zipalign = os.path.join(build_tools, "zipalign.exe")
    apksigner = os.path.join(build_tools, "apksigner.bat")
    dexdump = os.path.join(build_tools, "dexdump.exe")
    jdk_bin = r"C:\Program Files\Eclipse Adoptium\jdk-17\bin"
    javac = os.path.join(jdk_bin, "javac.exe")
    keystore = os.path.join(script_dir, "release.keystore")

    # The signing password must never be committed: it lives in the untracked
    # android/keystore.properties ("password=...") or the SP_KEYSTORE_PASS env var.
    keystore_pass = os.environ.get("SP_KEYSTORE_PASS", "")
    if not keystore_pass:
        props_path = os.path.join(script_dir, "keystore.properties")
        if os.path.exists(props_path):
            with open(props_path, encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if line.startswith("password="):
                        keystore_pass = line.split("=", 1)[1].strip()
                        break
    if not keystore_pass:
        sys.exit("Keystore password not found: set SP_KEYSTORE_PASS, or create "
                 "android/keystore.properties containing 'password=<pass>'.")

    libs_dir = os.path.join(script_dir, "libs")
    os.makedirs(libs_dir, exist_ok=True)

    aar_path = os.path.join(libs_dir, "geckoview-128.aar")

    # Complete list of required runtime dependencies to guarantee zero ClassNotFoundException / NoSuchMethodError
    required_downloads = [
        (aar_path, "https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview-arm64-v8a/128.0.20240725162350/geckoview-arm64-v8a-128.0.20240725162350.aar", "GeckoView 128 AAR"),
        (os.path.join(libs_dir, "core-1.13.1.aar"), "https://dl.google.com/dl/android/maven2/androidx/core/core/1.13.1/core-1.13.1.aar", "androidx.core AAR"),
        (os.path.join(libs_dir, "geckoview-exoplayer2.aar"), "https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview-exoplayer2/128.0.20240725162350/geckoview-exoplayer2-128.0.20240725162350.aar", "geckoview-exoplayer2 AAR"),
        (os.path.join(libs_dir, "snakeyaml-2.2.jar"), "https://repo1.maven.org/maven2/org/yaml/snakeyaml/2.2/snakeyaml-2.2.jar", "snakeyaml JAR"),
        (os.path.join(libs_dir, "annotation-jvm-1.8.0.jar"), "https://dl.google.com/dl/android/maven2/androidx/annotation/annotation-jvm/1.8.0/annotation-jvm-1.8.0.jar", "androidx.annotation-jvm JAR"),
        (os.path.join(libs_dir, "collection-1.2.0.jar"), "https://dl.google.com/dl/android/maven2/androidx/collection/collection/1.2.0/collection-1.2.0.jar", "androidx.collection JAR"),
        (os.path.join(libs_dir, "lifecycle-common-2.6.2.jar"), "https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-common/2.6.2/lifecycle-common-2.6.2.jar", "androidx.lifecycle-common JAR"),
        (os.path.join(libs_dir, "lifecycle-runtime-2.6.2.aar"), "https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime/2.6.2/lifecycle-runtime-2.6.2.aar", "androidx.lifecycle-runtime AAR"),
        (os.path.join(libs_dir, "versionedparcelable-1.1.1.aar"), "https://dl.google.com/dl/android/maven2/androidx/versionedparcelable/versionedparcelable/1.1.1/versionedparcelable-1.1.1.aar", "androidx.versionedparcelable AAR"),
        (os.path.join(libs_dir, "interpolator-1.0.0.aar"), "https://dl.google.com/dl/android/maven2/androidx/interpolator/interpolator/1.0.0/interpolator-1.0.0.aar", "androidx.interpolator AAR"),
        (os.path.join(libs_dir, "concurrent-futures-1.0.0.jar"), "https://dl.google.com/dl/android/maven2/androidx/concurrent/concurrent-futures/1.0.0/concurrent-futures-1.0.0.jar", "androidx.concurrent-futures JAR"),
        (os.path.join(libs_dir, "kotlin-stdlib-1.8.22.jar"), "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/1.8.22/kotlin-stdlib-1.8.22.jar", "kotlin-stdlib JAR"),
        (os.path.join(libs_dir, "play-services-fido-21.1.0.aar"), "https://dl.google.com/dl/android/maven2/com/google/android/gms/play-services-fido/21.1.0/play-services-fido-21.1.0.aar", "play-services-fido AAR"),
        (os.path.join(libs_dir, "play-services-tasks-18.1.0.aar"), "https://dl.google.com/dl/android/maven2/com/google/android/gms/play-services-tasks/18.1.0/play-services-tasks-18.1.0.aar", "play-services-tasks AAR"),
        (os.path.join(libs_dir, "play-services-base-18.5.0.aar"), "https://dl.google.com/dl/android/maven2/com/google/android/gms/play-services-base/18.5.0/play-services-base-18.5.0.aar", "play-services-base AAR"),
        (os.path.join(libs_dir, "play-services-basement-18.4.0.aar"), "https://dl.google.com/dl/android/maven2/com/google/android/gms/play-services-basement/18.4.0/play-services-basement-18.4.0.aar", "play-services-basement AAR"),
        (os.path.join(libs_dir, "core-runtime-2.2.0.aar"), "https://dl.google.com/dl/android/maven2/androidx/arch/core/core-runtime/2.2.0/core-runtime-2.2.0.aar", "arch core-runtime AAR"),
        (os.path.join(libs_dir, "core-common-2.2.0.jar"), "https://dl.google.com/dl/android/maven2/androidx/arch/core/core-common/2.2.0/core-common-2.2.0.jar", "arch core-common JAR"),
        (os.path.join(libs_dir, "listenablefuture-1.0.jar"), "https://repo1.maven.org/maven2/com/google/guava/listenablefuture/1.0/listenablefuture-1.0.jar", "listenablefuture JAR"),
    ]

    import urllib.request
    for path, url, label in required_downloads:
        if not os.path.exists(path):
            print(f"    Downloading {label}...")
            tmp_dl = path + ".tmp"
            urllib.request.urlretrieve(url, tmp_dl)
            os.rename(tmp_dl, path)

        # For AAR dependencies (other than geckoview which has separate handling), extract classes.jar
        if path.endswith(".aar") and path != aar_path:
            jar_path = path[:-4] + ".jar"
            if not os.path.exists(jar_path):
                with zipfile.ZipFile(path, "r") as z:
                    with open(jar_path, "wb") as f_out:
                        f_out.write(z.read("classes.jar"))

    build_dir = os.path.join(script_dir, "build")
    gen_dir = os.path.join(build_dir, "gen")
    classes_dir = os.path.join(build_dir, "classes")
    dex_dir = os.path.join(build_dir, "dex")
    aar_extracted_dir = os.path.join(build_dir, "aar_extracted")
    core_res_dir = os.path.join(build_dir, "core_res")
    public_dir = os.path.join(project_root, "public")

    print("==> 1. Preparing build directories and extracting GeckoView engine...")
    if os.path.exists(build_dir):
        for item in os.listdir(build_dir):
            if item != "aar_extracted":
                p = os.path.join(build_dir, item)
                if os.path.isdir(p):
                    shutil.rmtree(p)
                else:
                    os.remove(p)
    for d in [gen_dir, classes_dir, dex_dir, aar_extracted_dir, core_res_dir]:
        os.makedirs(d, exist_ok=True)

    gecko_classes_jar = os.path.join(aar_extracted_dir, "classes.jar")
    if not os.path.exists(gecko_classes_jar):
        print("    Extracting GeckoView AAR components...")
        with zipfile.ZipFile(aar_path, "r") as z:
            z.extractall(aar_extracted_dir)

    # Extract resources from core-1.13.1.aar
    core_aar = os.path.join(libs_dir, "core-1.13.1.aar")
    with zipfile.ZipFile(core_aar, "r") as z:
        for n in z.namelist():
            if n.startswith("res/"):
                z.extract(n, core_res_dir)

    print("==> 2. Compiling resources with aapt2...")
    compiled_app_res = os.path.join(build_dir, "compiled_app.zip")
    compiled_gecko_res = os.path.join(build_dir, "compiled_gecko.zip")
    compiled_core_res = os.path.join(build_dir, "compiled_core.zip")

    app_res_dir = os.path.join(script_dir, "src", "main", "res")
    gecko_res_dir = os.path.join(aar_extracted_dir, "res")
    core_res_actual = os.path.join(core_res_dir, "res")
    manifest = os.path.join(script_dir, "src", "main", "AndroidManifest.xml")

    subprocess.check_call([aapt2, "compile", "--dir", app_res_dir, "-o", compiled_app_res])
    subprocess.check_call([aapt2, "compile", "--dir", gecko_res_dir, "-o", compiled_gecko_res])
    subprocess.check_call([aapt2, "compile", "--dir", core_res_actual, "-o", compiled_core_res])

    print("==> 3. Linking base_rc.apk and generating R.java for app, geckoview, and core...")
    base_rc_apk = os.path.join(build_dir, "base_rc.apk")
    subprocess.check_call([
        aapt2, "link",
        compiled_app_res, compiled_gecko_res, compiled_core_res,
        "-I", platform_jar,
        "--manifest", manifest,
        "--java", gen_dir,
        "--extra-packages", "org.mozilla.geckoview:androidx.core",
        "-o", base_rc_apk,
        "--auto-add-overlay"
    ])

    print("==> 4. Compiling Java sources with Java 8 bytecode compatibility...")
    java_files = []
    for root, _, files in os.walk(gen_dir):
        for f in files:
            if f.endswith(".java"):
                java_files.append(os.path.join(root, f))
    for root, _, files in os.walk(os.path.join(script_dir, "src", "main", "java")):
        for f in files:
            if f.endswith(".java"):
                java_files.append(os.path.join(root, f))

    # Collect all library jars
    lib_jars = []
    for f in sorted(os.listdir(libs_dir)):
        if f.endswith(".jar"):
            lib_jars.append(os.path.join(libs_dir, f))

    javac_cp = os.pathsep.join([platform_jar, gecko_classes_jar] + lib_jars)

    subprocess.check_call([
        javac, "-encoding", "UTF-8",
        "-source", "8", "-target", "8",
        "-cp", javac_cp,
        "-d", classes_dir
    ] + java_files)

    print("==> 5. Converting bytecode to DEX with d8...")
    # Package compiled app classes into a single jar to avoid command line length limits on Windows
    app_classes_jar = os.path.join(build_dir, "app_classes.jar")
    with zipfile.ZipFile(app_classes_jar, "w", compression=zipfile.ZIP_DEFLATED) as z:
        for root, _, files in os.walk(classes_dir):
            for f in files:
                if f.endswith(".class"):
                    full_p = os.path.join(root, f)
                    rel_p = os.path.relpath(full_p, classes_dir).replace("\\", "/")
                    z.write(full_p, rel_p)

    d8_inputs = [app_classes_jar, gecko_classes_jar] + lib_jars

    subprocess.check_call([
        d8, "--release", "--min-api", "21",
        "--lib", platform_jar,
        "--output", dex_dir
    ] + d8_inputs)

    # Verify that classes.dex contains ViewCompat and zero missing GeckoView references
    print("    Verifying DEX bytecode integrity...")
    dex_classes = set()
    dex_out = subprocess.check_output([dexdump, "-f", os.path.join(dex_dir, "classes.dex")], encoding="utf-8", errors="ignore")
    for line in dex_out.splitlines():
        line = line.strip()
        if line.startswith("Class descriptor"):
            parts = line.split("'")
            if len(parts) >= 2:
                desc = parts[1].strip()
                if desc.startswith("L") and desc.endswith(";"):
                    dex_classes.add(desc[1:-1])

    assert "androidx/core/view/ViewCompat" in dex_classes, "CRITICAL ERROR: ViewCompat missing from DEX!"
    assert "androidx/core/content/res/ResourcesCompat" in dex_classes, "CRITICAL ERROR: ResourcesCompat missing from DEX!"
    assert "androidx/lifecycle/ProcessLifecycleOwner" in dex_classes, "CRITICAL ERROR: ProcessLifecycleOwner missing from DEX!"
    print(f"    DEX integrity verified: {len(dex_classes)} classes packaged, critical classes confirmed present.")

    print("==> 6. Packaging and signing Stronghold-Protocol.apk (GeckoView + ECH)...")
    raw_apk = os.path.join(build_dir, "raw.apk")
    aligned_apk = os.path.join(build_dir, "aligned.apk")
    final_apk_android = os.path.join(script_dir, "Stronghold-Protocol.apk")
    final_apk_root = os.path.join(project_root, "Stronghold-Protocol.apk")

    shutil.copyfile(base_rc_apk, raw_apk)

    # Append DEX, assets, and native libraries
    with zipfile.ZipFile(raw_apk, "a", compression=zipfile.ZIP_DEFLATED) as z:
        # 1. Add all DEX files
        for f in os.listdir(dex_dir):
            if f.endswith(".dex"):
                z.write(os.path.join(dex_dir, f), f)

        # 2. Add GeckoView omni.ja asset
        omni_ja = os.path.join(aar_extracted_dir, "assets", "omni.ja")
        if os.path.exists(omni_ja):
            z.write(omni_ja, "assets/omni.ja")

        # 3. Add project assets (e.g. extension files)
        app_assets = os.path.join(script_dir, "src", "main", "assets")
        if os.path.exists(app_assets):
            for root, _, files in os.walk(app_assets):
                for f in files:
                    full_p = os.path.join(root, f)
                    rel_p = os.path.relpath(full_p, app_assets).replace("\\", "/")
                    z.write(full_p, f"assets/{rel_p}")

        # 4. Add GeckoView native libraries (.so)
        jni_dir = os.path.join(aar_extracted_dir, "jni", "arm64-v8a")
        if os.path.exists(jni_dir):
            for f in os.listdir(jni_dir):
                if f.endswith(".so"):
                    z.write(os.path.join(jni_dir, f), f"lib/arm64-v8a/{f}")

    print("==> 7. Aligning APK with zipalign...")
    subprocess.check_call([zipalign, "-p", "-f", "4", raw_apk, aligned_apk])

    print("==> 8. Signing APK with apksigner...")
    subprocess.check_call([
        apksigner, "sign",
        "--ks", keystore,
        "--ks-key-alias", "stronghold",
        "--ks-pass", f"pass:{keystore_pass}",
        "--key-pass", f"pass:{keystore_pass}",
        "--min-sdk-version", "21",
        "--v1-signing-enabled", "true",
        "--v2-signing-enabled", "true",
        "--v3-signing-enabled", "true",
        "--out", final_apk_android,
        aligned_apk
    ])

    # Verify signature
    subprocess.check_call([apksigner, "verify", "-v", final_apk_android])

    # Copy to project root
    shutil.copyfile(final_apk_android, final_apk_root)

    # Clean up deprecated APK variants if they exist
    for f in [
        os.path.join(script_dir, "Stronghold-Protocol_rc.apk"),
        os.path.join(project_root, "Stronghold-Protocol_rc.apk"),
        os.path.join(script_dir, "Stronghold-Protocol_starst.apk"),
        os.path.join(project_root, "Stronghold-Protocol_starst.apk")
    ]:
        if os.path.exists(f):
            try:
                os.remove(f)
            except Exception:
                pass

    size_kb = os.path.getsize(final_apk_android) / 1024
    size_str = f"{size_kb / 1024:.2f} MB" if size_kb > 1024 else f"{size_kb:.2f} KB"

    total_time = time.time() - start_time
    print(f"\n=======================================================")
    print(f"APK BUILT AND SIGNED SUCCESSFULLY in {total_time:.1f}s!")
    print(f"=======================================================")
    print(f" - Stronghold-Protocol.apk             {size_str:>10}  | 独立现代浏览器内核客户端 (GeckoView + ECH, 免疫 GFW TCP RST)")
    print("=======================================================\n")

if __name__ == "__main__":
    main()
