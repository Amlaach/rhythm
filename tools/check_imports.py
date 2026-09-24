#!/usr/bin/env python3
"""
Finds names a Kotlin file uses but never imports.

This exists because of two builds lost to exactly that: `Locale` after the
lyrics split, and `fillMaxHeight` twice in the desktop shell. Both were one
missing line, both took a full CI run to find, and neither was visible by
reading the diff - an unimported name looks precisely like an imported one.

It is deliberately not a compiler. It answers one question, on two kinds of
name that a Kotlin file can only get from an import:

  types      - a capitalised name used on its own
  extensions - a lowercase extension called with a dot, from a known list

and it only reports a name that is not imported, not declared in the file,
not declared anywhere else in the same package, and not one Kotlin imports
into every file by default. Anything it is unsure about, it says nothing
about: a checker that cries wolf is one people stop running.
"""

import os
import re
import sys

# What Kotlin and the JVM put in every file without being asked.
DEFAULT = {
    # kotlin.*
    "Any", "Array", "Boolean", "BooleanArray", "Byte", "ByteArray", "Char",
    "CharArray", "CharSequence", "Comparable", "Deprecated", "DeprecationLevel",
    "Double", "DoubleArray", "Enum", "Exception", "Float", "FloatArray",
    "Function", "Int", "IntArray", "Lazy", "LazyThreadSafetyMode", "Long",
    "LongArray", "Nothing", "Number", "Pair", "Result", "Short", "ShortArray",
    "String", "Suppress", "Throwable", "Triple", "UByte", "UInt", "ULong",
    "UShort", "Unit", "OptIn", "JvmStatic", "JvmField", "JvmOverloads",
    "JvmName", "Throws", "Synchronized", "Volatile", "Transient", "Strictfp",
    "PublishedApi", "RequiresOptIn", "ExperimentalStdlibApi",
    # kotlin.collections.*
    "ArrayList", "Collection", "HashMap", "HashSet", "Iterable", "Iterator",
    "LinkedHashMap", "LinkedHashSet", "List", "ListIterator", "Map",
    "MutableCollection", "MutableIterable", "MutableIterator", "MutableList",
    "MutableListIterator", "MutableMap", "MutableSet", "Set",
    # kotlin.text.* / kotlin.ranges.* / kotlin.sequences.*
    "Appendable", "Charsets", "Regex", "MatchResult", "Regexp", "StringBuilder",
    "CharRange", "ClosedRange", "IntRange", "LongRange", "Sequence",
    # java.lang.*, which Kotlin/JVM also brings in
    "AutoCloseable", "ClassCastException", "Error", "IllegalArgumentException",
    "IllegalStateException", "IndexOutOfBoundsException", "Math",
    "NoSuchElementException", "NullPointerException", "NumberFormatException",
    "Runnable", "Runtime", "RuntimeException", "System", "Character", "Thread", "UnsupportedOperationException",
    "ArithmeticException", "InterruptedException", "StackOverflowError",
    "OutOfMemoryError", "Cloneable", "Comparator", "RegexOption", "MatchGroup",
    "MatchGroupCollection", "StringBuffer", "IntIterator", "Typography",
    "ConcurrentModificationException", "AssertionError", "NoWhenBranchMatchedException",
    "ArrayDeque", "SecurityException", "ClosedFloatingPointRange", "Object",
    "ClosedFloatingRange", "Class", "Iterable", "Annotation", "Function0",
    # the language's own words that happen to be capitalised in use
    "Companion", "Builder",
}

# Modifier extensions. A lowercase name is otherwise indistinguishable from a
# local variable or a member call - `.size(192)` on a Coil request builder is a
# member, `.size(48.dp)` on a Modifier is an import - so these are only checked
# inside a chain that actually starts at `Modifier`.
MODIFIER_EXT = {
    "fillMaxSize", "fillMaxWidth", "fillMaxHeight", "width", "height", "size",
    "padding", "offset", "wrapContentSize", "wrapContentWidth",
    "wrapContentHeight", "aspectRatio", "widthIn", "heightIn", "sizeIn",
    "defaultMinSize", "requiredSize", "requiredWidth", "requiredHeight",
    "imePadding", "navigationBarsPadding", "statusBarsPadding",
    "systemBarsPadding", "safeDrawingPadding", "windowInsetsPadding",
    "consumeWindowInsets", "displayCutoutPadding",
    "background", "border", "clickable", "combinedClickable", "scrollable",
    "verticalScroll", "horizontalScroll", "selectable", "toggleable",
    "focusable", "hoverable", "clip", "alpha", "rotate", "scale", "shadow",
    "blur", "drawBehind", "drawWithContent", "drawWithCache", "graphicsLayer",
    "zIndex", "onSizeChanged", "onGloballyPositioned", "pointerInput",
    "semantics", "testTag", "focusRequester", "onFocusChanged", "onKeyEvent",
    "paint", "animateContentSize",
}
# weight, align and matchParentSize are left out on purpose: they are members
# of RowScope, ColumnScope and BoxScope, in scope wherever a Row, Column or Box
# puts them, and need no import at all.

# Functions called by bare name, from a package that has to be imported.
# `delay(1000)` looks like any other call and is exactly the kind of miss this
# whole file exists for - it cost a build the first time it happened.
BARE_FUN = {
    "delay", "runBlocking", "coroutineScope", "supervisorScope", "withTimeout",
    "withTimeoutOrNull", "awaitAll", "yield", "channelFlow", "callbackFlow",
    "flowOf", "emptyFlow", "combine", "merge", "produceState", "rememberCoroutineScope",
    # The Compose state builders. Moving a block of UI from one file to
    # another takes its `remember { mutableStateOf(...) }` with it and leaves
    # the import behind, which is the single commonest way this breaks.
    "remember", "rememberSaveable", "mutableStateOf", "mutableIntStateOf",
    "mutableLongStateOf", "mutableFloatStateOf", "mutableDoubleStateOf",
    "mutableStateListOf", "mutableStateMapOf", "derivedStateOf",
}

# Names that are only ever an extension, wherever they appear.
PLAIN_EXT = {
    "collectAsState", "collectAsStateWithLifecycle", "stateIn", "shareIn",
    "flowOn", "distinctUntilChanged", "debounce", "conflate", "asPaddingValues",
    "rememberScrollState",
}

# A number with a Compose unit: `12.dp`, `1.5.sp`, `(x).dp` is left alone.
UNITS = re.compile(r"\b\d+(?:\.\d+)?\.(dp|sp)\b")

IMPORT = re.compile(r"^\s*import\s+([\w.]+)(?:\s+as\s+(\w+))?", re.M)
PACKAGE = re.compile(r"^\s*package\s+([\w.]+)", re.M)
# Anything declared at any level in a file: these need no import of their own.
DECLARED = re.compile(
    r"\b(?:class|interface|object|enum\s+class|annotation\s+class|data\s+class|"
    r"sealed\s+class|sealed\s+interface|data\s+object|value\s+class|typealias)\s+(\w+)"
)
# Functions at any indent: a member of an object or an interface is still a
# declaration, and JNA's `interface User32 { fun RegisterHotKey(...) }` is one.
ANY_FUN = re.compile(r"^\s*(?:@\w+\s+)*(?:internal\s+|private\s+|public\s+|override\s+)*"
                     r"(?:suspend\s+)?fun\s+(?:<[^>]*>\s+)?(?:[\w.<>?]+\.)?(\w+)", re.M)
# Properties at any indent, for the file that declares them.
ANY_VAL = re.compile(r"^\s*(?:@\w+\s+)*(?:internal\s+|private\s+|public\s+|override\s+)*"
                     r"(?:const\s+)?(?:val|var)\s+(\w+)", re.M)
# Properties at column zero only, for what the REST of the package may use.
#
# The difference matters: a local `val size` inside one function used to
# register `size` for every other file in the package, and an unimported
# Modifier.size then sailed straight through this check into a failed build.
TOP_VAL = re.compile(r"^(?:@\w+\s+)*(?:internal\s+|private\s+|public\s+)?"
                     r"(?:const\s+)?(?:val|var)\s+(\w+)", re.M)
TYPE_USE = re.compile(r"(?<![.\w@])([A-Z][A-Za-z0-9_]*)")
PLAIN_USE = re.compile(r"\.([a-z][A-Za-z0-9_]*)\s*\(")
# A chain that starts at Modifier, across as many lines as it runs for.
MOD_CHAIN = re.compile(r"\bModifier\b((?:\s*\.\s*\w+\s*(?:\([^()]*(?:\([^()]*\)[^()]*)*\))?)+)")
MOD_LINK = re.compile(r"\.\s*(\w+)")
BARE_USE = re.compile(r"(?<![.\w])([a-z][A-Za-z0-9_]*)\s*\(")
# Material icons are written Icons.Filled.Name, and the leaf still has to be
# imported by name. The general type rule cannot see it, because everything
# after a dot is a member access as far as that rule knows.
ICON_USE = re.compile(r"\bIcons\.(?:Filled|Outlined|Rounded|Sharp|TwoTone|"
                      r"AutoMirrored\.(?:Filled|Outlined|Rounded|Sharp|TwoTone))\.(\w+)")


def strip_noise(text: str) -> str:
    """Comments and string literals, blanked. Neither can reference a symbol."""
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    text = re.sub(r"//[^\n]*", " ", text)
    text = re.sub(r'"""(?:.|\n)*?"""', '""', text)
    text = re.sub(r'"(?:\\.|[^"\\\n])*"', '""', text)
    return text


# `val x by ...` / `var x by ...`, the Compose delegate forms.
DELEGATE_VAR = re.compile(r"\b(?:val|var)\s+\w+\s+by\s+(?:remember|rememberSaveable|vm\.|\w+\.collectAs)")
DELEGATE_MUTABLE = re.compile(r"\bvar\s+\w+\s+by\s+(?:remember|rememberSaveable)")


def scan(roots):
    files = []
    for root in roots:
        for base, _, names in os.walk(root):
            for name in names:
                if name.endswith(".kt"):
                    files.append(os.path.join(base, name))

    # What each package declares, so a sibling file in the same package counts
    # as available without an import - which in Kotlin it is.
    by_package = {}
    parsed = {}
    for path in files:
        raw = open(path, encoding="utf-8").read()
        body = strip_noise(raw)
        pkg = PACKAGE.search(raw)
        pkg = pkg.group(1) if pkg else ""
        declared = set(DECLARED.findall(body))
        functions = set(ANY_FUN.findall(body))
        # What this file itself may use without an import: everything it
        # declares, locals included.
        own = declared | functions | set(ANY_VAL.findall(body))
        # What the rest of the package may use: only what is actually visible
        # from outside this file.
        exported = declared | functions | set(TOP_VAL.findall(body))
        by_package.setdefault(pkg, set()).update(exported)
        parsed[path] = (raw, body, pkg, own)

    problems = []
    for path, (raw, body, pkg, own) in parsed.items():
        imported = set()
        imports_full = set()
        wildcards = False
        for full, alias in IMPORT.findall(raw):
            imports_full.add(full)
            if full.endswith(".*"):
                wildcards = True
                continue
            imported.add(alias or full.rsplit(".", 1)[-1])
        # A star import can supply anything, so a file that has one is not
        # something this check can say much about.
        if wildcards:
            continue

        available = imported | own | by_package.get(pkg, set()) | DEFAULT
        body_no_imports = IMPORT.sub(" ", body)

        for name in sorted(set(ICON_USE.findall(body_no_imports))):
            if name not in imported:
                problems.append((path, name, "icon"))
        for name in sorted(set(TYPE_USE.findall(body_no_imports))):
            # A single letter is a type parameter, not a type. ALL_CAPS is an
            # enum entry or a constant, and both resolve through scopes this
            # check cannot see - an enum `when` names its entries bare, and a
            # companion's constants are in scope throughout their own class.
            if len(name) == 1 or name.isupper():
                continue
            if name not in available:
                problems.append((path, name, "type"))
        used_ext = set()
        for chain in MOD_CHAIN.findall(body_no_imports):
            used_ext |= {n for n in MOD_LINK.findall(chain) if n in MODIFIER_EXT}
        used_ext |= {n for n in PLAIN_USE.findall(body_no_imports) if n in PLAIN_EXT}
        used_ext |= {n for n in BARE_USE.findall(body_no_imports) if n in BARE_FUN}
        # `12.dp` and `14.sp` are extensions too, imported one by one, and a
        # line of layout moved from one file to another brings the `.dp` and
        # leaves its import behind.
        used_ext |= {u for u in UNITS.findall(body_no_imports)}
        for name in sorted(used_ext):
            if name not in available:
                problems.append((path, name, "extension"))

        # Property delegation, which names nothing.
        #
        # `var x by remember { mutableStateOf(0) }` compiles to calls to
        # getValue and setValue, and those have to be imported - but the
        # source never writes either word, so nothing above can see the need.
        # Missing them does not fail where the delegate is: it fails wherever
        # the property is read, with an error about something else entirely,
        # which is a long way to walk back from.
        if DELEGATE_VAR.search(body_no_imports):
            if "androidx.compose.runtime.getValue" not in imports_full:
                problems.append((path, "getValue (for `by`)", "delegate"))
        if DELEGATE_MUTABLE.search(body_no_imports):
            if "androidx.compose.runtime.setValue" not in imports_full:
                problems.append((path, "setValue (for `by`)", "delegate"))
    return problems


if __name__ == "__main__":
    roots = sys.argv[1:] or ["app/src/main", "desktop/src/main", "engine/src/main"]
    found = scan(roots)
    for path, name, kind in found:
        print(f"{path}: {name} ({kind}) is used but never imported")
    if found:
        print(f"\n{len(found)} unimported name(s).")
        sys.exit(1)
    print("every name used is imported, declared, or built in")
