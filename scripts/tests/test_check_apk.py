"""Tests for the manifest parsing in scripts/check-apk.py (no build tools needed).
Run: python3 -m unittest discover -s scripts/tests"""
import importlib.util
import os
import tempfile
import unittest
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("check_apk", os.path.join(HERE, "..", "check-apk.py"))
ca = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ca)

# Shape of `aapt2 dump xmltree --file AndroidManifest.xml` (build tools 36), trimmed.
TREE = '''N: android=http://schemas.android.com/apk/res/android (line=6)
  E: manifest (line=6)
    A: package="tv.cleanyour.app" (Raw: "tv.cleanyour.app")
      E: application (line=21)
        A: http://schemas.android.com/apk/res/android:allowBackup(0x01010280)=false
        A: http://schemas.android.com/apk/res/android:networkSecurityConfig(0x01010527)=@0x7f120000
          E: activity (line=27)
            A: http://schemas.android.com/apk/res/android:name(0x01010003)="tv.cleanyour.app.MainActivity" (Raw: "tv.cleanyour.app.MainActivity")
            A: http://schemas.android.com/apk/res/android:exported(0x01010010)=true
              E: intent-filter (line=30)
                E: action (line=31)
                  A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.intent.action.MAIN" (Raw: "android.intent.action.MAIN")
          E: service (line=40)
            A: http://schemas.android.com/apk/res/android:name(0x01010003)="tv.cleanyour.app.OperationService" (Raw: "tv.cleanyour.app.OperationService")
            A: http://schemas.android.com/apk/res/android:exported(0x01010010)=false
          E: receiver (line=50)
            A: http://schemas.android.com/apk/res/android:name(0x01010003)="androidx.profileinstaller.ProfileInstallReceiver" (Raw: "androidx.profileinstaller.ProfileInstallReceiver")
            A: http://schemas.android.com/apk/res/android:permission(0x01010006)="android.permission.DUMP" (Raw: "android.permission.DUMP")
            A: http://schemas.android.com/apk/res/android:exported(0x01010010)=true
'''


class ManifestParsing(unittest.TestCase):
    def setUp(self):
        self.tree = ca.parse_tree(TREE)

    def test_application_attributes(self):
        app = ca.find(self.tree, "application")[0]
        self.assertEqual("false", app.attrs["allowBackup"])
        self.assertIn("networkSecurityConfig", app.attrs)
        self.assertNotIn("usesCleartextTraffic", app.attrs)

    def test_exported_components_with_clean_names(self):
        exported = ca.exported_components(self.tree)
        names = [c["name"] for c in exported]
        self.assertEqual(["tv.cleanyour.app.MainActivity", "androidx.profileinstaller.ProfileInstallReceiver"], names)
        receiver = exported[1]
        self.assertEqual("android.permission.DUMP", receiver["permission"])

    def test_old_boolean_encoding(self):
        t = ca.parse_tree('  E: manifest (line=1)\n    E: application (line=2)\n'
                          '      A: http://schemas.android.com/apk/res/android:debuggable(0x0101000f)=(type 0x12)0xffffffff\n')
        self.assertEqual("true", ca.find(t, "application")[0].attrs["debuggable"])


# `aapt2 dump xmltree` of the two network security configs (build tools 36), trimmed.
NSC_FULL = '''N: (line=0)
  E: network-security-config (line=6)
    E: base-config (line=7)
      A: cleartextTrafficPermitted=false
        E: trust-anchors (line=8)
          E: certificates (line=9)
            A: src="system" (Raw: "system")
    E: domain-config (line=12)
      A: cleartextTrafficPermitted=false
        E: domain (line=13)
          A: includeSubdomains=false
            T: 'github.com'
        E: domain (line=14)
          A: includeSubdomains=false
            T: 'api.github.com'
        E: domain (line=15)
          A: includeSubdomains=false
            T: 'objects.githubusercontent.com'
        E: domain (line=16)
          A: includeSubdomains=false
            T: 'release-assets.githubusercontent.com'
        E: certificateTransparency (line=17)
          A: enabled=true
'''
NSC_PLAY = '''N: (line=0)
  E: network-security-config (line=7)
    E: base-config (line=8)
      A: cleartextTrafficPermitted=false
        E: trust-anchors (line=9)
          E: certificates (line=10)
            A: src="system" (Raw: "system")
'''
RESOURCES = '''Package name=tv.cleanyour.app id=7f
  type xml id=12 entryCount=3
    resource 0x7f120000 xml/backup_rules
      () (file) res/A1.xml type=XML
    resource 0x7f120002 xml/network_security_config
      () (file) res/Kq.xml type=XML
'''


class StoreFlavours(unittest.TestCase):
    def test_the_github_hosts_match_download_hosts(self):
        with open(os.path.join(HERE, "..", "..", "core", "trust", "src", "main", "kotlin", "tv", "cleanyour", "core", "trust", "DownloadHosts.kt")) as f:
            src = f.read()
        block = src[src.index("val ALL"):src.index(")", src.index("val ALL"))]
        import re
        self.assertEqual(set(re.findall(r'"([^"]+)"', block)), ca.GITHUB_HOSTS)

    def test_the_network_config_file_is_looked_up_by_name(self):
        self.assertEqual("res/Kq.xml", ca.nsc_file(RESOURCES))
        self.assertIsNone(ca.nsc_file("Package name=tv.cleanyour.app id=7f\n"))

    def test_domains_and_flags_from_the_trees(self):
        domains, cleartext, user = ca.nsc_domains(ca.parse_tree(NSC_FULL))
        self.assertEqual(ca.GITHUB_HOSTS, domains)
        self.assertFalse(cleartext)
        self.assertFalse(user)
        self.assertEqual((set(), False, False), ca.nsc_domains(ca.parse_tree(NSC_PLAY)))
        bad = NSC_PLAY.replace("cleartextTrafficPermitted=false", "cleartextTrafficPermitted=true").replace('src="system"', 'src="user"')
        _, cleartext, user = ca.nsc_domains(ca.parse_tree(bad))
        self.assertTrue(cleartext and user)

    def test_full_must_name_exactly_the_github_hosts(self):
        self.assertTrue(all(ok for _, ok, _ in ca.host_checks("full", set(ca.GITHUB_HOSTS), True)))
        self.assertFalse(all(ok for _, ok, _ in ca.host_checks("full", {"github.com"}, True)))
        self.assertFalse(all(ok for _, ok, _ in ca.host_checks("full", ca.GITHUB_HOSTS | {"example.com"}, True)))

    def test_play_must_name_no_host_and_carry_no_release_check(self):
        self.assertTrue(all(ok for _, ok, _ in ca.host_checks("play", set(), False)))
        failed = lambda d, dex: [n for n, ok, _ in ca.host_checks("play", d, dex) if not ok]
        self.assertEqual(["no GitHub host in the network config (play)", "no host at all in the network config (play)"], failed({"api.github.com"}, False))
        self.assertEqual(["no host at all in the network config (play)"], failed({"example.com"}, False))
        self.assertEqual(["no GitHub release check in the code (play)"], failed(set(), True))

    def test_the_release_check_is_found_in_the_dex_of_an_apk_and_of_a_bundle(self):
        with tempfile.TemporaryDirectory() as d:
            apk, aab = os.path.join(d, "a.apk"), os.path.join(d, "a.aab")
            with zipfile.ZipFile(apk, "w") as z:
                z.writestr("classes.dex", b"dex\n035\x00 https://github.com/x")
                z.writestr("classes2.dex", b"dex\n035\x00 https://api.github.com/repos/udhaykumarbala/cleanyourtv-app/releases/latest")
            with zipfile.ZipFile(aab, "w") as z:
                z.writestr("BundleConfig.pb", b"")
                z.writestr("base/manifest/AndroidManifest.xml", b"")
                z.writestr("base/dex/classes.dex", b"dex\n035\x00 github.com api.github.com")
                z.writestr("base/assets/x.dex", b"api.github.com/repos/")   # not code the app runs as dex
            self.assertTrue(ca.dex_contains(apk, ca.RELEASE_CHECK))
            self.assertFalse(ca.dex_contains(aab, ca.RELEASE_CHECK, "base/dex/"))
            self.assertTrue(ca.is_bundle(aab))
            self.assertFalse(ca.is_bundle(apk))

    def test_the_bundle_base_module_becomes_a_proto_apk(self):
        with tempfile.TemporaryDirectory() as d:
            aab, out = os.path.join(d, "a.aab"), os.path.join(d, "proto.apk")
            with zipfile.ZipFile(aab, "w") as z:
                z.writestr("BundleConfig.pb", b"cfg")
                z.writestr("base/manifest/AndroidManifest.xml", b"manifest")
                z.writestr("base/resources.pb", b"table")
                z.writestr("base/res/Kq.xml", b"nsc")
                z.writestr("base/dex/classes.dex", b"dex")
                z.writestr("BUNDLE-METADATA/x", b"meta")
            ca.proto_apk_from_bundle(aab, out)
            with zipfile.ZipFile(out) as z:
                self.assertEqual(["AndroidManifest.xml", "resources.pb", "res/Kq.xml"], z.namelist())
                self.assertEqual(b"manifest", z.read("AndroidManifest.xml"))

    def test_a_signed_bundle_is_the_unsigned_build_plus_a_signature(self):
        with tempfile.TemporaryDirectory() as d:
            u, s, bad = (os.path.join(d, n) for n in ("u.aab", "s.aab", "bad.aab"))
            entries = {"BundleConfig.pb": b"cfg", "base/manifest/AndroidManifest.xml": b"m", "base/dex/classes.dex": b"dex"}
            with zipfile.ZipFile(u, "w") as z:
                for n, b in entries.items():
                    z.writestr(n, b)
            with zipfile.ZipFile(s, "w") as z:
                for n in ("META-INF/MANIFEST.MF", "META-INF/UPLOAD.SF", "META-INF/UPLOAD.RSA"):
                    z.writestr(n, b"sig")
                for n, b in entries.items():
                    z.writestr(n, b)
            self.assertEqual(([], [], []), ca.bundle_differences(s, u))
            with zipfile.ZipFile(bad, "w") as z:
                z.writestr("META-INF/UPLOAD.RSA", b"sig")
                z.writestr("BundleConfig.pb", b"cfg")
                z.writestr("base/dex/classes.dex", b"other dex")
                z.writestr("base/assets/extra.bin", b"x")
            self.assertEqual((["base/manifest/AndroidManifest.xml"], ["base/dex/classes.dex"], ["base/assets/extra.bin"]), ca.bundle_differences(bad, u))

    def test_a_test_build_switch_only_in_a_pre_release(self):
        # 0.4.6: -Pcyt.wizardStaysAnyBrand=true bundles a marker; a release never may.
        marker = "tv/cleanyour/core/planner/wizard-stays-any-brand"
        with tempfile.TemporaryDirectory() as d:
            apk, aab, clean = (os.path.join(d, n) for n in ("a.apk", "a.aab", "c.apk"))
            with zipfile.ZipFile(apk, "w") as z:
                z.writestr(marker, "true\n")
            with zipfile.ZipFile(aab, "w") as z:
                z.writestr("base/root/" + marker, "true\n")
            with zipfile.ZipFile(clean, "w") as z:
                z.writestr("classes.dex", b"dex")
            self.assertEqual([marker], ca.test_build_markers(apk, False))
            self.assertEqual([marker], ca.test_build_markers(aab, True))
            self.assertEqual([], ca.test_build_markers(clean, False))
            self.assertFalse(ca.test_build_check([marker], "0.4.6")[0])
            self.assertFalse(ca.test_build_check([marker], None)[0])
            for pre in ("0.4.6-beta1", "0.4.6-beta.1", "0.4.6-alpha", "0.4.6-rc2"):
                self.assertTrue(ca.test_build_check([marker], pre)[0], pre)
            self.assertTrue(ca.test_build_check([], "0.4.6")[0])

    def test_keytool_fingerprints(self):
        out = "Signer #1:\n\nCertificate #1:\nOwner: CN=x\nCertificate fingerprints:\n\t SHA1: 00:11\n\t SHA256: " + ":".join(["AB"] * 32) + "\n"
        self.assertEqual(["ab" * 32], ca.jar_certs(out))


if __name__ == "__main__":
    unittest.main()
