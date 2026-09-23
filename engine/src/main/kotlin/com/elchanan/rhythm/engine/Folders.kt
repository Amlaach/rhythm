package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.SongEntity

/**
 * The folders as they actually sit on the device: one inside another.
 *
 * The flat list this replaces showed every folder that contained a file, at
 * the same level, sorted by full path. On a phone where the music is a few
 * albums that is fine. On one where it is arranged - by artist, by year, by
 * where it came from - it is a hundred rows of near-identical paths with the
 * part that differs somewhere off the right hand edge, and the arrangement
 * the user built is exactly the information it throws away.
 *
 * Pure structure, no Android, so the whole of it can be checked without a
 * device.
 */
object Folders {

    /**
     * One folder.
     *
     * @param songs the files directly in it, not counting subfolders.
     * @param total everything underneath, which is what the row shows: a
     *   folder of folders would otherwise say it has no songs in it.
     */
    data class Node(
        val path: String,
        val name: String,
        val children: List<Node>,
        val songs: List<SongEntity>,
        val total: Int
    ) {
        val isLeaf: Boolean get() = children.isEmpty()
    }

    /**
     * Builds the tree, rooted at the deepest folder that contains everything.
     *
     * Starting at the real filesystem root would mean tapping through
     * `storage`, `emulated`, `0` before reaching anything, every time. Those
     * segments are shared by every file on the device and so they carry no
     * information at all.
     */
    fun build(songs: List<SongEntity>): Node {
        val byFolder = songs.groupBy { normalize(it.folder) }.filterKeys { it.isNotEmpty() }
        if (byFolder.isEmpty()) return Node("", "", emptyList(), emptyList(), 0)

        val paths = byFolder.keys.map { segments(it) }
        val shared = commonPrefix(paths)
        // One folder holding everything: show its contents rather than a tree
        // with a single branch.
        val rootSegments = if (byFolder.size == 1) shared.dropLast(1) else shared

        val root = Builder("")
        for ((folder, list) in byFolder) {
            val rest = segments(folder).drop(rootSegments.size)
            var node = root
            for (segment in rest) node = node.child(segment)
            node.songs.addAll(list)
        }
        val rootPath = rootSegments.joinToString("/")
        return collapse(root.toNode(rootPath, rootPath.substringAfterLast('/')))
    }

    /**
     * Finds a folder in the tree by its path.
     *
     * @return the node, or null when the path is not in this tree - which
     *   happens after a rescan removes a folder the screen was looking at.
     */
    fun find(root: Node, path: String): Node? {
        if (path == root.path) return root
        for (child in root.children) {
            if (path == child.path || path.startsWith(child.path + "/")) {
                return find(child, path)
            }
        }
        return null
    }

    /** Every folder from the root down to [path], for a breadcrumb. */
    fun trail(root: Node, path: String): List<Node> {
        val out = ArrayList<Node>()
        var node: Node? = root
        while (node != null) {
            out.add(node)
            if (node.path == path) break
            node = node.children.firstOrNull {
                path == it.path || path.startsWith(it.path + "/")
            }
        }
        return out
    }

    /** Everything under a folder, for playing it whole. */
    fun allSongs(node: Node): List<SongEntity> {
        val out = ArrayList<SongEntity>(node.total)
        collect(node, out)
        return out
    }

    private fun collect(node: Node, into: MutableList<SongEntity>) {
        into.addAll(node.songs)
        for (child in node.children) collect(child, into)
    }

    /**
     * Folds away folders that only exist to hold one other folder.
     *
     * A path like `Music/Hebrew/2024/Live` with nothing at any level but the
     * next folder down is four taps to reach one thing. File managers show
     * these joined up, and so does this: the row reads "Hebrew/2024/Live" and
     * goes straight there.
     */
    private fun collapse(node: Node): Node {
        val children = node.children.map { collapse(it) }
        if (node.songs.isEmpty() && children.size == 1 && node.path.isNotEmpty()) {
            val only = children.first()
            return only.copy(name = node.name + "/" + only.name)
        }
        return node.copy(children = children)
    }

    private class Builder(val name: String) {
        val songs = ArrayList<SongEntity>()
        private val children = LinkedHashMap<String, Builder>()

        fun child(segment: String): Builder = children.getOrPut(segment) { Builder(segment) }

        fun toNode(path: String, displayName: String): Node {
            val kids = children.values
                .map { it.toNode(if (path.isEmpty()) it.name else "$path/${it.name}", it.name) }
                .sortedBy { it.name.lowercase() }
            return Node(
                path = path,
                name = displayName,
                children = kids,
                songs = songs.sortedBy { it.titleLower },
                total = songs.size + kids.sumOf { it.total }
            )
        }
    }

    /** A song's folder the way the tree spells its paths, so it can be looked up with [find]. */
    fun pathOf(folder: String): String = segments(normalize(folder)).joinToString("/")

    private fun normalize(path: String): String =
        path.replace('\\', '/').trimEnd('/')

    private fun segments(path: String): List<String> =
        path.split('/').filter { it.isNotEmpty() }

    private fun commonPrefix(paths: List<List<String>>): List<String> {
        if (paths.isEmpty()) return emptyList()
        var prefix = paths.first()
        for (p in paths.drop(1)) {
            var i = 0
            while (i < prefix.size && i < p.size && prefix[i] == p[i]) i++
            prefix = prefix.take(i)
            if (prefix.isEmpty()) break
        }
        return prefix
    }
}
