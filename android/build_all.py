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

    print("==> 6. Preparing Starst overlay and linking base_starst.apk...")
    res_starst = os.path.join(build_dir, "res_starst")
    os.makedirs(os.path.join(res_starst, "values"), exist_ok=True)
    os.makedirs(os.path.join(res_starst, "values-zh"), exist_ok=True)

    with open(os.path.join(res_starst, "values", "strings.xml"), "w", encoding="utf-8") as f:
        f.write('''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Stronghold Protocol (Starst)</string>
    <string name="target_url">https://game.starst.site</string>
</resources>
''')

    with open(os.path.join(res_starst, "values-zh", "strings.xml"), "w", encoding="utf-8") as f:
        f.write('''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">卫戍协议：盟约 (Starst)</string>
    <string name="target_url">https://game.starst.site</string>
</resources>
''')

    compiled_starst = os.path.join(build_dir, "compiled_starst.zip")
    subprocess.check_call([aapt2, "compile", "--dir", res_starst, "-o", compiled_starst])

    base_starst_apk = os.path.join(build_dir, "base_starst.apk")
    subprocess.check_call([
        aapt2, "link",
        "-R", compiled_starst,
        compiled_res,
        "-I", platform_jar,
        "--manifest", manifest,
        "--rename-manifest-package", "top.rincynar.stronghold.starst",
        "--auto-add-overlay",
        "-o", base_starst_apk
    ])

    targets = [
        {
            "name": "Stronghold-Protocol_rc.apk",
            "base_apk": base_rc_apk,
            "desc": "RC 反代端 (在线客户端 ~41KB)"
        },
        {
            "name": "Stronghold-Protocol_starst.apk",
            "base_apk": base_starst_apk,
            "desc": "Starst 源站端 (在线客户端 ~41KB)"
        },
    ]

    output_apks = []

    for idx, target in enumerate(targets, 1):
        apk_name = target["name"]
        print(f"\n[{idx}/2] Building {apk_name} ({target['desc']})...")
        t_sub_start = time.time()

        raw_apk = os.path.join(build_dir, f"raw_{idx}.apk")
        aligned_apk = os.path.join(build_dir, f"aligned_{idx}.apk")
        final_apk_android = os.path.join(script_dir, apk_name)
        final_apk_root = os.path.join(project_root, apk_name)

        shutil.copyfile(target["base_apk"], raw_apk)

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

        size_kb = os.path.getsize(final_apk_android) / 1024
        size_str = f"{size_kb / 1024:.2f} MB" if size_kb > 1024 else f"{size_kb:.2f} KB"
        print(f"    Done in {time.time() - t_sub_start:.1f}s -> {apk_name} ({size_str})")
        output_apks.append((apk_name, size_str, target["desc"]))

    # Copy Stronghold-Protocol_rc.apk to Stronghold-Protocol.apk for default compatibility
    default_apk = os.path.join(project_root, "Stronghold-Protocol.apk")
    shutil.copyfile(os.path.join(project_root, "Stronghold-Protocol_rc.apk"), default_apk)
    shutil.copyfile(os.path.join(project_root, "Stronghold-Protocol_rc.apk"), os.path.join(script_dir, "Stronghold-Protocol.apk"))

    total_time = time.time() - start_time
    print(f"\n=======================================================")
    print(f"BOTH APKS BUILT AND SIGNED SUCCESSFULLY in {total_time:.1f}s!")
    print(f"=======================================================")
    for name, size, desc in output_apks:
        print(f" - {name:<35} {size:>10}  | {desc}")
    print("=======================================================\n")

if __name__ == "__main__":
    main()
