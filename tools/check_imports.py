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
    "Runnable", "RuntimeException", "System", "Thread", "UnsupportedOperationException",
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

# Names that are only ever an extension, wherever they appear.
PLAIN_EXT = {
    "collectAsState", "collectAsStateWithLifecycle", "stateIn", "shareIn",
    "flowOn", "distinctUntilChanged", "debounce", "conflate", "asPaddingValues",
    "rememberScrollState",
}

IMPORT = re.compile(r"^\s*import\s+([\w.]+)(?:\s+as\s+(\w+))?", re.M)
PACKAGE = re.compile(r"^\s*package\s+([\w.]+)", re.M)
# Anything declared at any level in a file: these need no import of their own.
DECLARED = re.compile(
    r"\b(?:class|interface|object|enum\s+class|annotation\s+class|data\s+class|"
    r"sealed\s+class|sealed\s+interface|data\s+object|value\s+class|typealias)\s+(\w+)"
)
TOP_FUN = re.compile(r"^\s*(?:@\w+\s+)*(?:internal\s+|private\s+|public\s+)?"
                     r"(?:suspend\s+)?fun\s+(?:<[^>]*>\s+)?(?:[\w.<>?]+\.)?(\w+)", re.M)
TOP_VAL = re.compile(r"^\s*(?:@\w+\s+)*(?:internal\s+|private\s+|public\s+)?"
                     r"(?:const\s+)?(?:val|var)\s+(\w+)", re.M)
TYPE_USE = re.compile(r"(?<![.\w@])([A-Z][A-Za-z0-9_]*)")
PLAIN_USE = re.compile(r"\.([a-z][A-Za-z0-9_]*)\s*\(")
# A chain that starts at Modifier, across as many lines as it runs for.
MOD_CHAIN = re.compile(r"\bModifier\b((?:\s*\.\s*\w+\s*(?:\([^()]*(?:\([^()]*\)[^()]*)*\))?)+)")
MOD_LINK = re.compile(r"\.\s*(\w+)")


def strip_noise(text: str) -> str:
    """Comments and string literals, blanked. Neither can reference a symbol."""
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    text = re.sub(r"//[^\n]*", " ", text)
    text = re.sub(r'"""(?:.|\n)*?"""', '""', text)
    text = re.sub(r'"(?:\\.|[^"\\\n])*"', '""', text)
    return text


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
        names = set(DECLARED.findall(body))
        names |= set(TOP_FUN.findall(body))
        names |= set(TOP_VAL.findall(body))
        by_package.setdefault(pkg, set()).update(names)
        parsed[path] = (raw, body, pkg, names)

    problems = []
    for path, (raw, body, pkg, own) in parsed.items():
        imported = set()
        wildcards = False
        for full, alias in IMPORT.findall(raw):
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
        for name in sorted(used_ext):
            if name not in available:
                problems.append((path, name, "extension"))
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
