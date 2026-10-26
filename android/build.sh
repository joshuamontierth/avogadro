#!/usr/bin/env bash
# Builds the signed release APK of the Avogadro Android app on a build host over SSH (one with
# the Android SDK in ~/Android/Sdk), driven from here. Every hop starts on this machine: no
# chained SSH.
#
#   android/build.sh            # build -> android/dist/avogadro.apk
#   android/build.sh install    # also install it with adb on the Windows host the phone is plugged into
#
# ../.env names the instance (SEARXNG_BASE_URL) and the hosts (AVOGADRO_BUILDER, AVOGADRO_INSTALLER);
# variables set in the environment win. The release key is created on the first build and copied
# back to android/keystore/ (gitignored); keep a backup, because an APK signed with a different
# key cannot update the installed app.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
setting() { # NAME: from the environment, else the last NAME= line of ../.env
  local value="${!1:-$(sed -n "s/^$1=//p" "$here/../.env" 2>/dev/null | tail -n 1)}"
  [ -n "$value" ] || { echo "$1 is not set (environment or $here/../.env)" >&2; exit 1; }
  printf '%s' "$value"
}
url="$(setting SEARXNG_BASE_URL)"
builder="$(setting AVOGADRO_BUILDER)"
remote=avogadro-build # in the builder's home directory
ssh_opts=(-o BatchMode=yes -o StrictHostKeyChecking=yes)

printf 'baseUrl=%s\n' "$url" > "$here/avogadro.properties"

echo "== syncing source to $builder:~/$remote"
rsync -a --delete --filter 'P keystore/' \
  --exclude build/ --exclude app/build/ --exclude .gradle/ --exclude .kotlin/ --exclude dist/ --exclude local.properties \
  -e "ssh ${ssh_opts[*]}" "$here/" "$builder:$remote/"

echo "== building on $builder"
ssh "${ssh_opts[@]}" "$builder" 'bash -s' <<'EOF'
set -euo pipefail
cd ~/avogadro-build
export ANDROID_HOME="$HOME/Android/Sdk"
sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

# compileSdk 37 and the build tools AGP 9.4 uses by default.
if [ ! -d "$ANDROID_HOME/platforms/android-37.0" ] || [ ! -d "$ANDROID_HOME/build-tools/36.0.0" ]; then
  echo "== installing SDK platform 37 and build-tools 36.0.0"
  yes | "$sdkmanager" --licenses > /dev/null || true
  "$sdkmanager" "platforms;android-37.0" "build-tools;36.0.0" > /dev/null
fi

# Release signing key: created once, never overwritten.
if [ ! -f keystore/avogadro.jks ]; then
  echo "== creating release keystore keystore/avogadro.jks"
  mkdir -p keystore && chmod 700 keystore
  pass=$(head -c 48 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 32)
  keytool -genkeypair -keystore keystore/avogadro.jks -storetype PKCS12 \
    -storepass "$pass" -keypass "$pass" -alias avogadro \
    -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=Avogadro" > /dev/null
  umask 077
  printf 'storeFile=avogadro.jks\nstorePassword=%s\nkeyAlias=avogadro\nkeyPassword=%s\n' "$pass" "$pass" \
    > keystore/keystore.properties
  chmod 600 keystore/keystore.properties keystore/avogadro.jks
fi

printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
chmod +x gradlew
./gradlew --console=plain :app:assembleRelease
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --print-certs app/build/outputs/apk/release/app-release.apk | head -n 2
EOF

echo "== fetching the APK (and the signing key, if new)"
mkdir -p "$here/dist"
rsync -a -e "ssh ${ssh_opts[*]}" "$builder:$remote/app/build/outputs/apk/release/app-release.apk" "$here/dist/avogadro.apk"
if [ ! -f "$here/keystore/avogadro.jks" ]; then
  rsync -a -e "ssh ${ssh_opts[*]}" "$builder:$remote/keystore/" "$here/keystore/"
  chmod 700 "$here/keystore" && chmod 600 "$here/keystore/"*
fi
echo "APK: $here/dist/avogadro.apk ($(stat -c %s "$here/dist/avogadro.apk") bytes)"

if [ "${1:-}" = install ]; then
  installer="$(setting AVOGADRO_INSTALLER)"
  echo "== installing through adb on $installer"
  # A streamed install fails on this phone with a blank error; --no-streaming pushes the APK first.
  scp -q "${ssh_opts[@]}" "$here/dist/avogadro.apk" "$installer:AppData/Local/Temp/avogadro.apk"
  ssh "${ssh_opts[@]}" "$installer" "adb install -r --no-streaming AppData\\Local\\Temp\\avogadro.apk & del AppData\\Local\\Temp\\avogadro.apk"
fi
