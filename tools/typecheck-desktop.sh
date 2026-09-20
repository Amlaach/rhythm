#!/bin/sh
# Compiles :engine and :desktop here, without the Android SDK.
#
# This exists because the sandbox can reach Maven Central but not Google's
# maven, so the Android plugin cannot be resolved and `./gradlew` fails before
# it compiles a line. Every desktop compile error then costs a full CI run,
# which is how two missing imports once cost four of them.
#
# Nothing Android is needed to typecheck the desktop build: :desktop has no
# Android dependency, and :engine has exactly one - Room's annotations, which
# are metadata that only KSP reads, and KSP does not run here. So this makes a
# throwaway copy of the tree with the Android plugin taken out of the build
# and those three annotations declared locally, and compiles that.
#
# It checks the desktop build only. :app still has to be built by CI.
set -e

SRC=$(cd "$(dirname "$0")/.." && pwd)
OUT=${RHYTHM_TYPECHECK_OUT:-${TMPDIR:-/tmp}/rhythm-typecheck}

rm -rf "$OUT"
mkdir -p "$OUT"
# The working tree, not the commit: the point is to check what is about to be
# committed, and a new file is untracked right up until it is added.
(cd "$SRC" && tar -cf - \
    --exclude=.git --exclude=build --exclude=.gradle --exclude=.idea .) \
    | tar -xf - -C "$OUT"

cd "$OUT"

python3 - <<'PY'
s = open('build.gradle.kts', encoding='utf-8').read()
s = '\n'.join(
    line for line in s.split('\n')
    if 'com.android.application' not in line
    and 'kotlin.android' not in line
    and 'devtools.ksp' not in line
)
open('build.gradle.kts', 'w', encoding='utf-8').write(s)

s = open('settings.gradle.kts', encoding='utf-8').read()
start = s.find('        google {')
if start != -1:
    end = s.find('        }\n', s.find('        }\n', start) + 1) + len('        }\n')
    s = s[:start] + s[end:]
s = s.replace('        google()\n', '')
s = s.replace('include(":app")\n', '')
open('settings.gradle.kts', 'w', encoding='utf-8').write(s)

s = open('engine/build.gradle.kts', encoding='utf-8').read()
s = s.replace('api("androidx.room:room-common:2.6.1")', '// stubbed locally by tools/typecheck-desktop.sh')
s += '\nsourceSets["main"].java.srcDir("roomstub")\n'
open('engine/build.gradle.kts', 'w', encoding='utf-8').write(s)
PY

mkdir -p engine/roomstub/androidx/room
cat > engine/roomstub/androidx/room/Stubs.kt <<'EOF'
package androidx.room

// Metadata only. The real ones come from room-common on Google's maven, and
// the only thing that reads them is the KSP pass over in :app, which is not
// part of this check.
annotation class Entity(
    val tableName: String = "",
    val indices: Array<Index> = [],
    val primaryKeys: Array<String> = []
)
annotation class Index(vararg val value: String, val unique: Boolean = false)
annotation class PrimaryKey(val autoGenerate: Boolean = false)
annotation class ColumnInfo(val name: String = "")
annotation class Ignore
EOF

sh gradlew :desktop:compileKotlin --console=plain "$@"
