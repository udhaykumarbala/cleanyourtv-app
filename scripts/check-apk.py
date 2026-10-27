#!/usr/bin/env python3
"""Release APK and AAB assertions (PRODUCT-SPEC 7.3 items 3 and 5, SECURITY 6.4 item 1, STORES-PLAN 1).

Runs aapt2 and apksigner from the build tools (and jarsigner and keytool from the JDK for an AAB)
and checks:
  - package tv.cleanyour.app, minSdkVersion 28, targetSdkVersion 36, and, when given, the
    versionCode and versionName;
  - not debuggable, not testOnly, allowBackup false, no cleartext traffic, a network security
    config;
  - exactly the D16 permissions (plus the app's own signature permission that androidx.core adds),
    the same set for both store flavours;
  - one exported component (ProfileInstaller's receiver behind android.permission.DUMP is listed
    separately: only the shell and the system can reach it);
  - the hosts of the network security config, per store flavour (STORES-PLAN 1): `full` (GitHub
    and F-Droid) names exactly the four GitHub hosts of DownloadHosts; `play` (Google Play) names
    no host at all and its code carries no GitHub release check;
  - no test-build switch (0.4.6: core/planner's wizard-stays-any-brand marker, which widens R0 to
    every brand) unless the versionName is a pre-release (X.Y.Z-alphaN, -betaN or -rcN), so a
    release can never carry one while a test build from the same scripts can;
  - an APK: signed with v2 and v3, not v1 (minSdk 28), and, when --expect-cert is given, by that
    certificate (SHA-256 of the DER certificate, lower-case hex);
  - an AAB: the base module's manifest and resources (converted with aapt2 into an APK and checked
    as above), no dependency metadata block, and, unless --unsigned, a JAR signature (the upload
    key) that verifies, by the --expect-cert certificate when given; with --unsigned-build, every
    entry of that unsigned bundle is in this one with the same bytes and nothing else is added but
    the signature files (the bundle's counterpart of apksigcopier compare).

  check-apk.py app.apk|app.aab [--flavour full|play] [--expect-cert HEX] [--unsigned]
               [--expect-version-code N] [--expect-version-name X.Y.Z]
               [--unsigned-build app-unsigned.aab] [--json out.json]
  check-apk.py --print-permissions [--flavour full|play]

--print-permissions prints the android.permission names a build of the flavour requests, one per
line, sorted (scripts/check-installed.sh compares an installed app with them).

The flavour defaults to full. Exit 0 when every check passes, 1 when one fails, 2 on a usage or
tool error. Stdlib only.
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

PACKAGE = "tv.cleanyour.app"
MIN_SDK = 28
TARGET_SDK = 36
D16 = {
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.CHANGE_WIFI_MULTICAST_STATE",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE",
    "android.permission.POST_NOTIFICATIONS",
    # RFC-W8-7 (accepted): the credential prompt (BiometricPrompt with the device credential).
    "android.permission.USE_BIOMETRIC",
}
# androidx.core declares and uses <applicationId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
# (signature level) for receivers registered at run time; it grants nothing to other apps.
OWN_SIGNATURE_PERMISSION = PACKAGE + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
NEVER = {
    "android.permission.NEARBY_WIFI_DEVICES", "android.permission.ACCESS_WIFI_STATE",
    "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE",
    "android.permission.WAKE_LOCK", "android.permission.REQUEST_INSTALL_PACKAGES", "android.permission.CAMERA",
    "android.permission.QUERY_ALL_PACKAGES", "android.permission.RECEIVE_BOOT_COMPLETED",
}
DUMP_GUARDED_OK = {"androidx.profileinstaller.ProfileInstallReceiver"}

# DownloadHosts.ALL in :core:trust (scripts/tests checks that the two lists agree).
GITHUB_HOSTS = {
    "github.com",
    "api.github.com",
    "objects.githubusercontent.com",
    "release-assets.githubusercontent.com",
}
FLAVOURS = ("full", "play")
# The full build's update check (src/full Store.kt); the play build must not carry it.
RELEASE_CHECK = b"api.github.com/repos/"


def expected_permissions(flavour):
    """The permissions a build of this store flavour requests: D16, the same for full and play."""
    if flavour not in FLAVOURS:
        raise ValueError(f"unknown flavour {flavour}")
    return set(D16)


# AGP's dependency block in a bundle; dependenciesInfo.includeInBundle = false keeps it out.
AAB_DEPENDENCY_BLOCK = "BUNDLE-METADATA/com.android.tools.build.libraries/dependencies.pb"


def run(cmd):
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=180)
    except FileNotFoundError:
        print(f"error: {cmd[0]} not found; put the build tools (and a JDK for an AAB) on PATH", file=sys.stderr)
        sys.exit(2)
    return p.returncode, p.stdout, p.stderr


def tool(cmd, what):
    code, out, err = run(cmd)
    if code != 0:
        print(f"error: {what} failed: {err.strip()}", file=sys.stderr)
        sys.exit(2)
    return out


def badging(apk):
    return tool(["aapt2", "dump", "badging", apk], "aapt2 dump badging")


def manifest_tree(apk):
    return tool(["aapt2", "dump", "xmltree", "--file", "AndroidManifest.xml", apk], "aapt2 dump xmltree")


class Node:
    def __init__(self, tag, depth):
        self.tag, self.depth, self.attrs, self.children, self.text = tag, depth, {}, [], []


def parse_tree(text):
    """aapt2 xmltree output into a small element tree (E: element, A: attribute, T: text lines)."""
    root = Node("#root", -1)
    stack = [root]
    for line in text.splitlines():
        m = re.match(r"^(\s*)E: (\S+)", line)
        if m:
            depth = len(m.group(1))
            while stack[-1].depth >= depth:
                stack.pop()
            n = Node(m.group(2), depth)
            stack[-1].children.append(n)
            stack.append(n)
            continue
        m = re.match(r"^(\s*)T: (?:'(.*)'|\"(.*)\")\s*$", line)
        if m and len(stack) > 1:
            depth = len(m.group(1))
            while stack[-1].depth >= depth and len(stack) > 1:
                stack.pop()
            stack[-1].text.append((m.group(2) if m.group(2) is not None else m.group(3)).strip())
            continue
        m = re.match(r"^\s*A: (?:http://schemas.android.com/apk/res/android:)?([A-Za-z]+)(?:\(0x[0-9a-f]+\))?=(.*)$", line)
        if m and len(stack) > 1:
            name, raw = m.group(1), m.group(2).strip()
            val = raw
            q = re.match(r'^"([^"]*)"', raw)
            if q:
                val = q.group(1)
            elif re.match(r"^\(type 0x12\)0x0$", raw):
                val = "false"
            elif re.match(r"^\(type 0x12\)0x[f]+$", raw):
                val = "true"
            elif raw.startswith("@"):
                val = raw
            stack[-1].attrs[name] = val
    return root


def find(node, tag):
    out = []
    for c in node.children:
        if c.tag == tag:
            out.append(c)
        out.extend(find(c, tag))
    return out


def exported_components(tree):
    app = (find(tree, "application") or [None])[0]
    result = []
    if app is None:
        return result
    for kind in ("activity", "activity-alias", "service", "receiver", "provider"):
        for c in find(app, kind):
            name = c.attrs.get("name", "?")
            exported = c.attrs.get("exported")
            has_filter = bool(find(c, "intent-filter"))
            is_exported = exported == "true" or (exported is None and has_filter)
            if is_exported:
                result.append({"kind": kind, "name": name, "permission": c.attrs.get("permission")})
    return result


def nsc_file(resources_dump):
    """The file of the xml/network_security_config resource in `aapt2 dump resources` output. A
    release build shortens resource paths (res/Xy.xml), so the name is looked up, never assumed."""
    lines = resources_dump.splitlines()
    for i, line in enumerate(lines):
        if re.search(r"\bresource 0x[0-9a-f]+ xml/network_security_config\b", line):
            for nxt in lines[i + 1:i + 6]:
                m = re.search(r"\(file\) (\S+)", nxt)
                if m:
                    return m.group(1)
            return None
    return None


def nsc_domains(tree):
    """Every <domain> host named in a network security config tree, and whether it permits
    cleartext or user CAs anywhere."""
    domains = set()
    for d in find(tree, "domain"):
        domains.update(t for t in d.text if t)
    cleartext = any(n.attrs.get("cleartextTrafficPermitted") == "true" for n in find(tree, "base-config") + find(tree, "domain-config"))
    user_cas = any(n.attrs.get("src") == "user" for n in find(tree, "certificates"))
    return domains, cleartext, user_cas


def host_checks(flavour, domains, dex_has_release_check):
    """(name, ok, detail) per flavour: full names exactly the GitHub hosts; play none, and no code
    for the GitHub release check."""
    if flavour == "full":
        return [("network config hosts are the GitHub hosts (full)", domains == GITHUB_HOSTS,
                 f"extra={sorted(domains - GITHUB_HOSTS)} missing={sorted(GITHUB_HOSTS - domains)}")]
    github = sorted(h for h in domains if h in GITHUB_HOSTS or h.endswith(".github.com") or h.endswith("githubusercontent.com"))
    return [
        ("no GitHub host in the network config (play)", not github, ",".join(github)),
        ("no host at all in the network config (play)", not domains, ",".join(sorted(domains))),
        ("no GitHub release check in the code (play)", not dex_has_release_check, "api.github.com/repos/ found" if dex_has_release_check else ""),
    ]


def dex_contains(path, needle, prefix=""):
    with zipfile.ZipFile(path) as z:
        for n in z.namelist():
            if n.startswith(prefix) and re.match(r"^(classes\d*\.dex)$", n[len(prefix):]):
                if needle in z.read(n):
                    return True
    return False


# Test-build switches carried as bundled marker files (a Gradle property each, off by default). In an
# APK a Java resource sits at the root, in a bundle under base/root/.
TEST_BUILD_MARKERS = ("tv/cleanyour/core/planner/wizard-stays-any-brand",)
PRERELEASE = re.compile(r"^\d+\.\d+\.\d+-(alpha|beta|rc)\.?\d*$")


def test_build_markers(path, bundle):
    """The test-build marker files in an APK or a bundle's base module."""
    prefix = "base/root/" if bundle else ""
    with zipfile.ZipFile(path) as z:
        names = set(z.namelist())
    return sorted(m for m in TEST_BUILD_MARKERS if prefix + m in names)


def test_build_check(markers, version_name):
    """(ok, detail): a test-build switch only in a pre-release version."""
    if not markers:
        return True, ""
    pre = bool(version_name and PRERELEASE.match(version_name))
    return pre, f"{','.join(markers)} in {'pre-release' if pre else 'release'} {version_name or '?'}"


def is_bundle(path):
    try:
        with zipfile.ZipFile(path) as z:
            names = set(z.namelist())
    except zipfile.BadZipFile:
        return False
    return "base/manifest/AndroidManifest.xml" in names and "BundleConfig.pb" in names


def proto_apk_from_bundle(aab, out):
    """The base module of an AAB as a proto-format APK (what aapt2 convert reads): the manifest at
    the root, resources.pb and res/ beside it."""
    with zipfile.ZipFile(aab) as z, zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as o:
        for n in z.namelist():
            if n == "base/manifest/AndroidManifest.xml":
                o.writestr("AndroidManifest.xml", z.read(n))
            elif n == "base/resources.pb" or n.startswith("base/res/"):
                o.writestr(n[len("base/"):], z.read(n))
    return out


JAR_SIGNATURE = re.compile(r"^META-INF/(MANIFEST\.MF|[^/]+\.(SF|RSA|DSA|EC))$")


def bundle_differences(signed, unsigned):
    """What differs between a jarsigned bundle and the unsigned build it came from, beyond the
    signature files: entries missing, changed or added."""
    with zipfile.ZipFile(signed) as s, zipfile.ZipFile(unsigned) as u:
        sn = {i.filename: i for i in s.infolist()}
        un = {i.filename: i for i in u.infolist()}
        missing = sorted(set(un) - set(sn))
        added = sorted(n for n in set(sn) - set(un) if not JAR_SIGNATURE.match(n))
        changed = sorted(n for n in set(un) & set(sn) if un[n].CRC != sn[n].CRC or s.read(n) != u.read(n))
    return missing, changed, added


def signatures(apk):
    # --min-sdk-version 24: verify every scheme present. With the APK's own minSdk (28) apksigner
    # checks v3 alone and reports v2 as false even when the v2 block is there.
    code, out, err = run(["apksigner", "verify", "--verbose", "--print-certs", "--min-sdk-version", "24", apk])
    info = {"verified": code == 0, "error": err.strip() if code else ""}
    for scheme in ("v1", "v2", "v3", "v3.1", "v4"):
        m = re.search(rf"Verified using {re.escape(scheme)} scheme \(.*?\): (true|false)", out)
        info[scheme] = m and m.group(1) == "true"
    info["certs"] = re.findall(r"certificate SHA-256 digest: ([0-9a-f]{64})", out)
    return info


def jar_certs(printcert_output):
    """SHA-256 fingerprints from `keytool -printcert -jarfile`, as lower-case hex without colons."""
    return [m.replace(":", "").lower() for m in re.findall(r"SHA256:\s*([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){31})", printcert_output)]


def bundle_signature(aab):
    # Not -strict: an upload key is self-signed, and -strict turns that warning into a failure.
    code, out, err = run(["jarsigner", "-verify", aab])
    verified = code == 0 and "jar verified" in out and "unsigned entries" not in out
    _, certs_out, _ = run(["keytool", "-printcert", "-jarfile", aab])
    return {"verified": verified, "error": (err or out).strip() if not verified else "", "certs": jar_certs(certs_out)}


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("apk", nargs="?", help="an APK, or an AAB (the base module is checked)")
    ap.add_argument("--flavour", choices=FLAVOURS, default="full", help="store flavour of the build (default full)")
    ap.add_argument("--expect-cert", help="signer certificate SHA-256 (hex): the release key for an APK, the upload key for an AAB")
    ap.add_argument("--unsigned", action="store_true", help="skip the signature checks (unsigned build output)")
    ap.add_argument("--expect-version-code", type=int, help="the versionCode the build must carry")
    ap.add_argument("--expect-version-name", help="the versionName the build must carry")
    ap.add_argument("--unsigned-build", help="AAB only: the unsigned build this signed bundle must equal, apart from its signature")
    ap.add_argument("--json", help="write the findings as JSON here")
    ap.add_argument("--print-permissions", action="store_true", help="print the permissions the flavour requests and exit")
    a = ap.parse_args(argv)
    if a.print_permissions:
        for perm in sorted(expected_permissions(a.flavour)):
            print(perm)
        return 0
    if not a.apk:
        ap.error("an APK or AAB is required")
    if not shutil.which("aapt2"):
        print("error: aapt2 not on PATH (build-tools)", file=sys.stderr)
        return 2

    checks = []

    def check(name, ok, detail=""):
        checks.append({"check": name, "ok": bool(ok), "detail": detail})

    bundle = is_bundle(a.apk)
    scratch = tempfile.mkdtemp(prefix="check-apk-")
    try:
        apk = a.apk
        if bundle:
            with zipfile.ZipFile(a.apk) as z:
                names = set(z.namelist())
            check("bundle has a base module", "base/manifest/AndroidManifest.xml" in names and "base/resources.pb" in names)
            check("bundle has no dependency metadata block", AAB_DEPENDENCY_BLOCK not in names)
            proto = proto_apk_from_bundle(a.apk, os.path.join(scratch, "base-proto.apk"))
            apk = os.path.join(scratch, "base.apk")
            tool(["aapt2", "convert", "--output-format", "binary", "-o", apk, proto], "aapt2 convert (bundle base module)")

        b = badging(apk)
        pkg = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", b)
        check("package name", pkg and pkg.group(1) == PACKAGE, pkg.group(1) if pkg else "missing")
        if a.expect_version_code is not None:
            check(f"versionCode {a.expect_version_code}", pkg and int(pkg.group(2)) == a.expect_version_code, pkg.group(2) if pkg else "missing")
        if a.expect_version_name is not None:
            check(f"versionName {a.expect_version_name}", pkg and pkg.group(3) == a.expect_version_name, pkg.group(3) if pkg else "missing")
        ok, detail = test_build_check(test_build_markers(a.apk, bundle), pkg.group(3) if pkg else None)
        check("no test-build switch unless a pre-release", ok, detail)
        min_sdk = re.search(r"^(?:minSdkVersion|sdkVersion):'(\d+)'", b, re.M)
        check("minSdkVersion 28", min_sdk and int(min_sdk.group(1)) == MIN_SDK, min_sdk.group(1) if min_sdk else "missing")
        tgt = re.search(r"^targetSdkVersion:'(\d+)'", b, re.M)
        check("targetSdkVersion 36", tgt and int(tgt.group(1)) == TARGET_SDK, tgt.group(1) if tgt else "missing")
        check("not debuggable", "application-debuggable" not in b)
        check("not testOnly", "testOnly" not in b)

        perms = set(re.findall(r"^uses-permission: name='([^']+)'", b, re.M))
        wanted = expected_permissions(a.flavour)
        extra = sorted(perms - wanted - {OWN_SIGNATURE_PERMISSION})
        missing = sorted(wanted - perms)
        check("permissions equal D16", not extra and not missing, f"extra={extra} missing={missing}")
        check("no forbidden permission", not (perms & NEVER), ",".join(sorted(perms & NEVER)))

        tree = parse_tree(manifest_tree(apk))
        app = (find(tree, "application") or [Node("application", 0)])[0]
        check("usesCleartextTraffic not true", app.attrs.get("usesCleartextTraffic") != "true", app.attrs.get("usesCleartextTraffic", "absent"))
        check("network security config present", "networkSecurityConfig" in app.attrs, app.attrs.get("networkSecurityConfig", "absent"))
        check("allowBackup false", app.attrs.get("allowBackup") == "false", app.attrs.get("allowBackup", "absent"))

        exported = exported_components(tree)
        dump_guarded = [c for c in exported if c["name"] in DUMP_GUARDED_OK and c["permission"] == "android.permission.DUMP"]
        others = [c for c in exported if c not in dump_guarded]
        check("one exported component", len(others) == 1,
              "; ".join(f"{c['kind']} {c['name']}" for c in others) +
              (f" (plus {len(dump_guarded)} behind DUMP: {', '.join(c['name'] for c in dump_guarded)})" if dump_guarded else ""))

        # STORES-PLAN 1: the hosts, per flavour.
        nsc = nsc_file(tool(["aapt2", "dump", "resources", apk], "aapt2 dump resources"))
        check("network security config file found", nsc is not None, nsc or "missing")
        if nsc is not None:
            domains, cleartext, user_cas = nsc_domains(parse_tree(tool(["aapt2", "dump", "xmltree", "--file", nsc, apk], "aapt2 dump xmltree (network config)")))
            check("network config permits no cleartext", not cleartext)
            check("network config trusts no user CAs", not user_cas)
            release_check = dex_contains(a.apk, RELEASE_CHECK, "base/dex/" if bundle else "")
            for name, ok, detail in host_checks(a.flavour, domains, release_check):
                check(name, ok, detail)

        if not a.unsigned and not bundle:
            s = signatures(a.apk)
            check("apksigner verify", s["verified"], s["error"])
            check("v1 signing off (minSdk 28)", not s["v1"])
            check("v2 signature", s["v2"])
            check("v3 signature", s["v3"])
            if a.expect_cert:
                check("signer certificate", a.expect_cert.lower() in s["certs"], ",".join(s["certs"]))
        elif not a.unsigned and bundle:
            s = bundle_signature(a.apk)
            check("jarsigner verify (bundle)", s["verified"], s["error"])
            check("one bundle signer", len(set(s["certs"])) == 1, ",".join(s["certs"]))
            if a.expect_cert:
                check("bundle signer certificate", a.expect_cert.lower() in s["certs"], ",".join(s["certs"]))
        if bundle and a.unsigned_build:
            missing, changed, added = bundle_differences(a.apk, a.unsigned_build)
            check("bundle is the unsigned build plus a signature", not (missing or changed or added),
                  f"missing={missing[:5]} changed={changed[:5]} added={added[:5]}")
    finally:
        shutil.rmtree(scratch, ignore_errors=True)

    width = max(len(c["check"]) for c in checks)
    for c in checks:
        print(f"{'ok  ' if c['ok'] else 'FAIL'} {c['check']:<{width}}  {c['detail']}".rstrip())
    failed = [c for c in checks if not c["ok"]]
    print(f"{len(checks) - len(failed)} of {len(checks)} checks passed for {a.apk} ({a.flavour}{', bundle' if bundle else ''})")
    if a.json:
        with open(a.json, "w") as f:
            json.dump({"apk": a.apk, "flavour": a.flavour, "bundle": bundle, "checks": checks}, f, indent=2)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
