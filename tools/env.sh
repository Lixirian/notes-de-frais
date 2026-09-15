#!/usr/bin/env bash
# Localise le SDK Android et un JDK 17+ (celui embarqué dans Android Studio suffit).
# Utilisé par run.sh, build-apk.sh, preview.sh et tools/create-avds.sh.

if [ -z "${ANDROID_HOME:-}" ]; then
  for candidate in "${ANDROID_SDK_ROOT:-}" "$LOCALAPPDATA/Android/Sdk" "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
    if [ -n "$candidate" ] && [ -d "$candidate" ]; then export ANDROID_HOME="$candidate"; break; fi
  done
fi
[ -n "${ANDROID_HOME:-}" ] || { echo "SDK Android introuvable : définissez ANDROID_HOME" >&2; exit 1; }

if [ -z "${JAVA_HOME:-}" ]; then
  for candidate in "/c/Program Files/Android/Android Studio/jbr" "/Applications/Android Studio.app/Contents/jbr/Contents/Home" "/opt/android-studio/jbr"; do
    if [ -d "$candidate" ]; then export JAVA_HOME="$candidate"; break; fi
  done
fi
[ -n "${JAVA_HOME:-}" ] || { echo "JDK introuvable : définissez JAVA_HOME (JDK 17 ou plus récent)" >&2; exit 1; }

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) EXE=".exe"; BAT=".bat" ;;
  *) EXE=""; BAT="" ;;
esac
export ADB="$ANDROID_HOME/platform-tools/adb$EXE"
export EMULATOR="$ANDROID_HOME/emulator/emulator$EXE"
export AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager$BAT"
export SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager$BAT"

# local.properties pour Gradle (chemin Windows avec ':' échappé)
if [ ! -f local.properties ]; then
  if [ -n "$EXE" ]; then
    win="$(cygpath -w "$ANDROID_HOME" | sed 's/\\/\\\\/g; s/:/\\:/')"
    echo "sdk.dir=$win" > local.properties
  else
    echo "sdk.dir=$ANDROID_HOME" > local.properties
  fi
fi
