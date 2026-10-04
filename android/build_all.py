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
    jdk_bin = r"C:\Program Files\Eclipse Adoptium\jdk-17\bin"
    javac = os.path.join(jdk_bin, "javac.exe")
    keystore = os.path.join(script_dir, "release.keystore")

    libs_dir = os.path.join(script_dir, "libs")
    aar_path = os.path.join(libs_dir, "geckoview-128.aar")
    androidx_annotation = os.path.join(libs_dir, "annotation-1.6.0.jar")
    androidx_collection = os.path.join(libs_dir, "collection-1.2.0.jar")
    androidx_lifecycle = os.path.join(libs_dir, "lifecycle-common-2.6.2.jar")

    # Auto-download missing libraries
    os.makedirs(libs_dir, exist_ok=True)
    required_downloads = [
        (aar_path, "https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview-arm64-v8a/128.0.20240725162350/geckoview-arm64-v8a-128.0.20240725162350.aar", "GeckoView 128 AAR"),
        (androidx_annotation, "https://dl.google.com/dl/android/maven2/androidx/annotation/annotation/1.6.0/annotation-1.6.0.jar", "androidx.annotation"),
        (androidx_collection, "https://dl.google.com/dl/android/maven2/androidx/collection/collection/1.2.0/collection-1.2.0.jar", "androidx.collection"),
        (androidx_lifecycle, "https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-common/2.6.2/lifecycle-common-2.6.2.jar", "androidx.lifecycle")
    ]
    for path, url, label in required_downloads:
        if not os.path.exists(path):
            print(f"    Downloading {label}...")
            import urllib.request
            urllib.request.urlretrieve(url, path)

    build_dir = os.path.join(script_dir, "build")
    gen_dir = os.path.join(build_dir, "gen")
    classes_dir = os.path.join(build_dir, "classes")
    dex_dir = os.path.join(build_dir, "dex")
    aar_extracted_dir = os.path.join(build_dir, "aar_extracted")
    public_dir = os.path.join(project_root, "public")

    print("==> 1. Preparing build directories and extracting GeckoView engine...")
    if os.path.exists(build_dir):
        # Keep aar_extracted if it exists to speed up rebuilds
        for item in os.listdir(build_dir):
            if item != "aar_extracted":
                p = os.path.join(build_dir, item)
                if os.path.isdir(p):
                    shutil.rmtree(p)
                else:
                    os.remove(p)
    for d in [gen_dir, classes_dir, dex_dir, aar_extracted_dir]:
        os.makedirs(d, exist_ok=True)

    gecko_classes_jar = os.path.join(aar_extracted_dir, "classes.jar")
    if not os.path.exists(gecko_classes_jar):
        print("    Extracting GeckoView AAR components...")
        with zipfile.ZipFile(aar_path, "r") as z:
            z.extractall(aar_extracted_dir)

    print("==> 2. Compiling base resources with aapt2...")
    compiled_res = os.path.join(build_dir, "compiled_res.zip")
    res_dir = os.path.join(script_dir, "src", "main", "res")
    manifest = os.path.join(script_dir, "src", "main", "AndroidManifest.xml")
    
    subprocess.check_call([aapt2, "compile", "--dir", res_dir, "-o", compiled_res])

    print("==> 3. Linking base_rc.apk and generating R.java...")
    base_rc_apk = os.path.join(build_dir, "base_rc.apk")
    subprocess.check_call([
        aapt2, "link", compiled_res,
        "-I", platform_jar,
        "--manifest", manifest,
        "--java", gen_dir,
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

    javac_cp = os.pathsep.join([
        platform_jar,
        gecko_classes_jar,
        androidx_annotation,
        androidx_collection,
        androidx_lifecycle
    ])

    subprocess.check_call([
        javac, "-encoding", "UTF-8",
        "-source", "8", "-target", "8",
        "-cp", javac_cp,
        "-d", classes_dir
    ] + java_files)

    print("==> 5. Converting bytecode to DEX with d8...")
    class_files = []
    for root, _, files in os.walk(classes_dir):
        for f in files:
            if f.endswith(".class"):
                class_files.append(os.path.join(root, f))

    d8_inputs = class_files + [
        gecko_classes_jar,
        androidx_annotation,
        androidx_collection,
        androidx_lifecycle
    ]

    subprocess.check_call([
        d8, "--release", "--min-api", "21",
        "--lib", platform_jar,
        "--output", dex_dir
    ] + d8_inputs)

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
        "--ks-pass", "pass:123456",
        "--key-pass", "pass:123456",
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
