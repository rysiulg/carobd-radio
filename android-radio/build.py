"""Buduje APK z lokalnego Android SDK. Parametry ścieżek i środowiska opisano w README.md."""
import argparse
import os
import pathlib
import secrets
import subprocess
import tempfile
import zipfile
from build_config import API, MIN_API, BUILD_TOOLS, KEY_ALIAS, PACKAGE_NAME, VERSION
ROOT = pathlib.Path(__file__).resolve().parent
IS_WIN = os.name == "nt"

def run(*args, env=None):
    """Uruchamia lokalne narzędzie i przerywa po niepowodzeniu. args zawiera osobne argumenty."""
    subprocess.run([str(x) for x in args], env=env, check=True)

def build():
    """Przyjmuje --sdk, --out i --keys. Zachowuje lokalny klucz dla aktualizacji tego samego APK."""
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--sdk", default=os.getenv("ANDROID_SDK_ROOT") or os.getenv("ANDROID_HOME"))
    p.add_argument("--out", type=pathlib.Path, default=ROOT / "dist")
    p.add_argument("--keys", type=pathlib.Path, default=ROOT.parent / "private")
    args = p.parse_args()
    if not args.sdk:
        p.error("Podaj --sdk lub ANDROID_HOME.")
    sdk = pathlib.Path(args.sdk).resolve()
    tools = sdk / "build-tools" / BUILD_TOOLS
    android = sdk / "platforms" / ("android-" + str(API)) / "android.jar"
    exe = ".exe" if IS_WIN else ""
    jhome = os.getenv("JAVA_HOME")
    java_bin = pathlib.Path(jhome) / "bin" if jhome else None
    def jtool(name):
        return str(java_bin / (name + exe)) if java_bin else name
    args.out.mkdir(parents=True, exist_ok=True)
    args.keys.mkdir(parents=True, exist_ok=True)
    key = args.keys.resolve() / "radio-key.p12"
    password = args.keys.resolve() / "store.pass"
    if not key.exists() and not password.exists():
        with password.open("x", encoding="ascii") as f:
            f.write(secrets.token_urlsafe(32))
        run(jtool("keytool"), "-genkeypair", "-keystore", key, "-storetype", "PKCS12", "-storepass:file", password, "-alias", KEY_ALIAS, "-keyalg", "RSA", "-keysize", 3072, "-validity", 9000, "-dname", "CN=CarOBD RadioKIA")
    if not (key.is_file() and password.is_file()):
        raise RuntimeError("Niekompletna para plików podpisu. Przywróć ją z kopii.")
    target = args.out.resolve() / f"{PACKAGE_NAME}_v{VERSION}.apk"
    with tempfile.TemporaryDirectory(dir=args.out) as tmp:
        d = pathlib.Path(tmp).resolve()
        classes = d / "classes"
        classes.mkdir()
        apk = d / "unsigned.apk"
        res = d / "resources.zip"
        generated = d / "generated"
        run(tools / ("aapt2" + exe), "compile", "--dir", ROOT / "res", "-o", res)
        run(tools / ("aapt2" + exe), "link", "-o", apk, "-I", android, "--manifest", ROOT / "AndroidManifest.xml", "--java", generated, res)
        sources = sorted((ROOT / "src").rglob("*.java")) + sorted(generated.rglob("*.java"))
        run(jtool("javac"), "--release", "8", "-encoding", "UTF-8", "-classpath", android, "-d", classes, *sources)
        archive = d / "app-classes.jar"
        with zipfile.ZipFile(archive, "w") as z:
            for f in sorted(classes.rglob("*.class")):
                z.write(f, f.relative_to(classes).as_posix())
        run(jtool("java"), "-cp", tools / "lib/d8.jar", "com.android.tools.r8.D8", "--release", "--min-api", MIN_API, "--lib", android, "--output", d, archive)
        with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
            z.write(d / "classes.dex", "classes.dex")
        aligned = d / "aligned.apk"
        run(tools / ("zipalign" + exe), "-f", "4", apk, aligned)
        signer = tools / "lib/apksigner.jar"
        signed = d / "signed.apk"
        run(jtool("java"), "-jar", signer, "sign", "--ks", key, "--ks-key-alias", KEY_ALIAS, "--ks-pass", "file:" + str(password), "--v4-signing-enabled", "false", "--out", signed, aligned)
        run(jtool("java"), "-jar", signer, "verify", "--verbose", "--print-certs", signed)
        run(tools / ("zipalign" + exe), "-c", "4", signed)
        signed.replace(target)
    print(target)

if __name__ == "__main__":
    build()
