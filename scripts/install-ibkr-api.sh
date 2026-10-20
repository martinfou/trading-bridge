#!/usr/bin/env bash
# Installs the official Interactive Brokers TWS API (TwsApi.jar) into a project-local
# Maven repository at lib/m2 so the build can depend on com.interactivebrokers:tws-api.
#
# The jar is NOT committed (IB license). Run this once per checkout / CI / Docker build:
#   ./scripts/install-ibkr-api.sh            # stable channel (default)
#   IBKR_API_CHANNEL=latest ./scripts/install-ibkr-api.sh
#
# Idempotent: exits early if the expected version is already installed.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHANNEL="${IBKR_API_CHANNEL:-stable}"
EXPECTED_VERSION="${TWS_API_VERSION:-1050.02}"   # keep in sync with <tws.api.version> in pom.xml
WORK="$ROOT/lib/.ibapi-download"
REPO="$ROOT/lib/m2"
GROUP=com.interactivebrokers
ARTIFACT=tws-api
BASE_URL="https://download2.interactivebrokers.com/installers/tws-api/${CHANNEL}"

installed_jar="$REPO/com/interactivebrokers/$ARTIFACT/$EXPECTED_VERSION/$ARTIFACT-$EXPECTED_VERSION.jar"
if [[ -f "$installed_jar" && "${FORCE:-0}" != "1" ]]; then
  echo "TWS API $EXPECTED_VERSION already installed at $installed_jar"
  exit 0
fi

mkdir -p "$WORK"
rm -rf "$WORK/IBJts"

case "$(uname -s)-$(uname -m)" in
  Darwin-arm64)  installer="tws-api-${CHANNEL}-macos-arm.dmg" ;;
  Darwin-x86_64) installer="tws-api-${CHANNEL}-macosx-x64.dmg" ;;
  Linux-*)       installer="tws-api-${CHANNEL}-linux-x64.sh" ;;
  *) echo "Unsupported platform $(uname -s)-$(uname -m)" >&2; exit 1 ;;
esac

echo "Downloading $BASE_URL/$installer ..."
curl -fsSL -o "$WORK/$installer" "$BASE_URL/$installer"

# install4j unattended install (-q) into $WORK/IBJts
if [[ "$installer" == *.dmg ]]; then
  mount_point="$(hdiutil attach -nobrowse -readonly "$WORK/$installer" | awk -F'\t' '/\/Volumes\//{print $NF}' | tail -1)"
  trap 'hdiutil detach "$mount_point" >/dev/null 2>&1 || true' EXIT
  app="$(find "$mount_point" -maxdepth 1 -name '*Installer.app' | head -1)"
  "$app/Contents/MacOS/JavaApplicationStub" -q -dir "$WORK/IBJts"
else
  sh "$WORK/$installer" -q -dir "$WORK/IBJts"
fi

version="$(sed -n 's/^API_Version=//p' "$WORK/IBJts/API_VersionNum.txt" | tr -d '[:space:]')"
jar="$WORK/IBJts/source/javaclient/TwsApi.jar"
[[ -f "$jar" ]] || { echo "TwsApi.jar not found in installer output" >&2; exit 1; }

if [[ "$version" != "$EXPECTED_VERSION" ]]; then
  echo "WARNING: downloaded TWS API $version but pom expects $EXPECTED_VERSION." >&2
  echo "         Installing as $version; update <tws.api.version> in pom.xml to use it." >&2
fi

MVN="$ROOT/mvnw"; [[ -x "$MVN" ]] || MVN=mvn
"$MVN" -q install:install-file \
  -Dfile="$jar" -DgroupId=$GROUP -DartifactId=$ARTIFACT -Dversion="$version" \
  -Dpackaging=jar -DgeneratePom=true -DlocalRepositoryPath="$REPO"

rm -f "$WORK/$installer"
echo "Installed $GROUP:$ARTIFACT:$version into $REPO"
