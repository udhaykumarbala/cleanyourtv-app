#!/usr/bin/env bash
# Build the bundled launcher APK (com.gathin.tvhome) on forge from the pinned upstream commit, sign
# it with the dedicated launcher release key (PRODUCT-SPEC Q1), and place it in the app's assets.
#
#   scripts/build-launcher-on-forge.sh            # build twice, sign both, compare, copy into assets
#
# The upstream submodule must be checked out at PIN (third_party/cleanyourtv). Each build runs in a
# fresh copy of the sources and a throwaway eclipse-temurin:21-jdk container (upstream uses AGP 8.5
# and Gradle 8.9; JDK 21 as F-Droid's buildserver uses, and launcher 2.5 builds byte for byte the same
# on 17 and 21) under the same host lock as scripts/build-on-forge.sh. Reproducibility: upstream's
# release build carries AGP's own signing block, so the raw APKs differ; both are re-signed with the
# launcher key and the two signed APKs must be byte for byte identical before one is kept. Signing
# runs in a third throwaway container: the keystore
# and its password travel over the ssh stdin into the container's /dev/shm and never touch forge's
# disk. Key custody: $CYT_SECRETS/cleanyourtv-launcher/ on the maintainer's machine only.
# The signature block of the signed APK is extracted into android/platform/signatures/ for the F-Droid
# rebuild (scripts/fdroid-build-tv-apks.sh); commit it with the APK.
# After a rebuild, update PinnedLauncher in core/trust with the printed sha256, size and signer digest;
# the verifyLauncherPin task fails the build until they agree.
set -euo pipefail
cd "$(dirname "$0")/.."

PIN=d7b0d4a5284d3934a1fce192bd4e2f43aa1fbf97
. scripts/lib/forge.sh
SECRETS="${SECRETS:-$CYT_SECRETS/cleanyourtv-launcher}"
IMAGE="eclipse-temurin:21-jdk"
VOLUME="mitv-android-cache"
BUILD_TOOLS="34.0.0"
RUN_ID="cyt-launcher-$(date +%Y%m%d%H%M%S)-$$"
OUT="android/platform/src/main/assets/bundled/cleanyourtv-launcher.apk"
CMDLINE_TOOLS="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"

head=$(git -C third_party/cleanyourtv rev-parse HEAD)
[ "$head" = "$PIN" ] || { echo "third_party/cleanyourtv is at $head, expected $PIN" >&2; exit 1; }
[ -z "$(git -C third_party/cleanyourtv status --porcelain)" ] || { echo "submodule has local changes" >&2; exit 1; }
[ -r "$SECRETS/launcher-release.p12" ] && [ -r "$SECRETS/keystore-password.txt" ] || { echo "launcher key not found in $SECRETS" >&2; exit 1; }

forge_init
REMOTE_DIR="$FORGE_ROOT/$RUN_ID"

cleanup() {
  "${SSH[@]}" "docker rm -f '$RUN_ID-a' '$RUN_ID-b' '$RUN_ID-sign' >/dev/null 2>&1 || true; rm -rf '$REMOTE_DIR'" </dev/null || true
}
trap cleanup EXIT INT TERM

# One build in a fresh copy of the launcher sources and a fresh container.
build() {
  local name=$1 dir="$REMOTE_DIR/$1"
  echo "==> rsync launcher sources at ${PIN:0:7} to $FORGE:$dir"
  "${SSH[@]}" "mkdir -p '$dir'" </dev/null
  rsync -az --delete -e "ssh -i $SSH_KEY -o BatchMode=yes" \
    --exclude .gradle/ --exclude build/ --exclude local.properties \
    third_party/cleanyourtv/launcher/ "$FORGE:$dir/"
  echo "==> build $name: assembleRelease in $IMAGE"
  "${SSH[@]}" "flock -w 3600 '$LOCK' docker run --rm --name '$RUN_ID-$name' --memory 3g --cpus 4 \
    -e HOME=/cache/home -e OWNER=\$(id -u):\$(id -g) \
    -v $VOLUME:/cache -v '$dir':/src -w /src $IMAGE bash -euc '
      export ANDROID_HOME=/cache/sdk GRADLE_USER_HOME=/cache/gradle
      trap \"chown -R \$OWNER /src\" EXIT
      mkdir -p /cache/home
      if [ ! -x \$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager ]; then
        mkdir -p \$ANDROID_HOME/cmdline-tools && cd \$ANDROID_HOME/cmdline-tools
        curl -fsSL -o tools.zip $CMDLINE_TOOLS && jar xf tools.zip && rm tools.zip
        rm -rf latest && mv cmdline-tools latest && chmod +x latest/bin/*
        cd /src
      fi
      yes | \$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses >/dev/null
      \$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager \"platforms;android-34\" \"build-tools;$BUILD_TOOLS\" >/dev/null
      echo sdk.dir=\$ANDROID_HOME > local.properties
      chmod +x gradlew
      # Launcher 2.5 can build under another application ID (gradle property launcherApplicationId, upstream
      # #377). The app pins com.gathin.tvhome, so name it here and check the badging below.
      ./gradlew assembleRelease --no-daemon --no-build-cache --console=plain -q -Dorg.gradle.jvmargs=-Xmx1536m \
        -PlauncherApplicationId=com.gathin.tvhome
      ls -l app/build/outputs/apk/release/
    '" </dev/null
}

build a
build b

echo "==> sign both builds with the launcher release key (in memory), compare and verify"
{ cat "$SECRETS/keystore-password.txt"; printf '\n'; base64 < "$SECRETS/launcher-release.p12"; } | \
"${SSH[@]}" "flock -w 3600 '$LOCK' docker run -i --rm --name '$RUN_ID-sign' --memory 1g --cpus 2 \
  -e OWNER=\$(id -u):\$(id -g) \
  -v $VOLUME:/cache -v '$REMOTE_DIR':/src -w /src $IMAGE bash -euc '
    trap \"rm -f /dev/shm/ks.p12 /dev/shm/ks.pass; chown -R \$OWNER /src\" EXIT
    umask 077
    IFS= read -r pass; printf %s \"\$pass\" > /dev/shm/ks.pass; unset pass
    base64 -d > /dev/shm/ks.p12
    BT=/cache/sdk/build-tools/$BUILD_TOOLS
    for b in a b; do
      in=\$(ls \$b/app/build/outputs/apk/release/*.apk | head -1)
      \$BT/zipalign -c -p 4 \"\$in\"
      \$BT/apksigner sign --ks /dev/shm/ks.p12 --ks-type PKCS12 --ks-pass file:/dev/shm/ks.pass \
        --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false \
        --out /src/signed-\$b.apk \"\$in\"
    done
    rm -f /dev/shm/ks.p12 /dev/shm/ks.pass
    sha256sum /src/signed-a.apk /src/signed-b.apk
    cmp /src/signed-a.apk /src/signed-b.apk || { echo \"the two signed builds differ: not reproducible\" >&2; exit 1; }
    echo \"identical after signing: yes\"
    mv /src/signed-a.apk /src/cleanyourtv-launcher.apk
    \$BT/apksigner verify --verbose --print-certs /src/cleanyourtv-launcher.apk | grep -E \"^Verified using|^Signer #1 certificate (DN|SHA-256)|^Number of signers\"
    badging=\$(\$BT/aapt2 dump badging /src/cleanyourtv-launcher.apk | head -1)
    echo \"\$badging\"
    printf %s \"\$badging\" | grep -q \"^package: name=.com[.]gathin[.]tvhome. \" || { echo \"not the com.gathin.tvhome package\" >&2; exit 1; }
    sha256sum /src/cleanyourtv-launcher.apk; stat -c \"size %s\" /src/cleanyourtv-launcher.apk
  '"

mkdir -p "$(dirname "$OUT")"
scp -q -i "$SSH_KEY" "$FORGE:$REMOTE_DIR/cleanyourtv-launcher.apk" "$OUT"
forge_extract_signature "$REMOTE_DIR/cleanyourtv-launcher.apk" android/platform/signatures/cleanyourtv-launcher
echo "==> $OUT"
shasum -a 256 "$OUT"
wc -c < "$OUT" | tr -d ' ' | sed 's/^/size /'
