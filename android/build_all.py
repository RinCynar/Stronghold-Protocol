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

    build_dir = os.path.join(script_dir, "build")
    gen_dir = os.path.join(build_dir, "gen")
    classes_dir = os.path.join(build_dir, "classes")
    dex_dir = os.path.join(build_dir, "dex")
    public_dir = os.path.join(project_root, "public")

    print("==> 1. Preparing build directories...")
    if os.path.exists(build_dir):
        shutil.rmtree(build_dir)
    for d in [gen_dir, classes_dir, dex_dir]:
        os.makedirs(d, exist_ok=True)

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

    subprocess.check_call([
        javac, "-encoding", "UTF-8",
        "-source", "8", "-target", "8",
        "-cp", platform_jar,
        "-d", classes_dir
    ] + java_files)

    print("==> 5. Converting bytecode to DEX with d8...")
    class_files = []
    for root, _, files in os.walk(classes_dir):
        for f in files:
            if f.endswith(".class"):
                class_files.append(os.path.join(root, f))

    subprocess.check_call([
        d8, "--release", "--min-api", "21",
        "--lib", platform_jar,
        "--output", dex_dir
    ] + class_files)

    classes_dex = os.path.join(dex_dir, "classes.dex")

    print("==> 6. Packaging and signing Stronghold-Protocol.apk...")
    raw_apk = os.path.join(build_dir, "raw.apk")
    aligned_apk = os.path.join(build_dir, "aligned.apk")
    final_apk_android = os.path.join(script_dir, "Stronghold-Protocol.apk")
    final_apk_root = os.path.join(project_root, "Stronghold-Protocol.apk")

    shutil.copyfile(base_rc_apk, raw_apk)

    # Append classes.dex
    with zipfile.ZipFile(raw_apk, "a", compression=zipfile.ZIP_DEFLATED) as z:
        z.write(classes_dex, "classes.dex")

    # Zipalign
    subprocess.check_call([zipalign, "-p", "-f", "4", raw_apk, aligned_apk])

    # Apksigner
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

    # Also keep Stronghold-Protocol_rc.apk for backwards compatibility
    rc_apk_android = os.path.join(script_dir, "Stronghold-Protocol_rc.apk")
    rc_apk_root = os.path.join(project_root, "Stronghold-Protocol_rc.apk")
    shutil.copyfile(final_apk_android, rc_apk_android)
    shutil.copyfile(final_apk_android, rc_apk_root)

    # Clean up deprecated Starst APKs if they exist
    for f in [
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
    print(f" - Stronghold-Protocol.apk             {size_str:>10}  | 官方自建反代客户端 (目标 https://ak.s.rincynar.top)")
    print(f" - Stronghold-Protocol_rc.apk          {size_str:>10}  | 兼容别名")
    print("=======================================================\n")

if __name__ == "__main__":
    main()
