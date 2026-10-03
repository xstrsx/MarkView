package com.markview.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Authorization-boundary tests for SAF tree document ids (MV-01).
 *
 * The rules under test are the ones that decide whether a document may be treated as
 * living inside an authorised folder — i.e. whether its relative images are readable at
 * all — so they are worth pinning down precisely.
 */
class TreePathPolicyTest {

    @Test
    fun `a document directly inside the tree root has an empty relative path`() {
        assertEquals("", TreePathPolicy.relativePathWithinTree("primary:Docs", "primary:Docs"))
    }

    @Test
    fun `hierarchical document ids yield the path below the tree root`() {
        assertEquals(
            "sub/note.md",
            TreePathPolicy.relativePathWithinTree("primary:Docs/sub/note.md", "primary:Docs"),
        )
        assertEquals(
            "图片 目录/a b.svg",
            TreePathPolicy.relativePathWithinTree("primary:Docs/图片 目录/a b.svg", "primary:Docs"),
        )
    }

    @Test
    fun `a shared prefix without a separator is not inside the tree`() {
        // "primary:Docs2/x.md" must never be treated as living in "primary:Docs".
        assertNull(TreePathPolicy.relativePathWithinTree("primary:Docs2/x.md", "primary:Docs"))
        assertNull(TreePathPolicy.relativePathWithinTree("primary:Documents/a.md", "primary:Docs"))
    }

    @Test
    fun `opaque ids are unrelated to a hierarchical tree id`() {
        assertNull(TreePathPolicy.relativePathWithinTree("msf:1000000123", "primary:Docs"))
        assertNull(TreePathPolicy.relativePathWithinTree("primary:Docs/a.md", "msf:1000000123"))
    }

    @Test
    fun `directory segments drop the file name`() {
        assertEquals(emptyList<String>(), TreePathPolicy.directorySegments(""))
        assertEquals(emptyList<String>(), TreePathPolicy.directorySegments("note.md"))
        assertEquals(listOf("sub"), TreePathPolicy.directorySegments("sub/note.md"))
        assertEquals(listOf("a", "b"), TreePathPolicy.directorySegments("a/b/note.md"))
    }

    @Test
    fun `file name is the last segment`() {
        assertEquals("note.md", TreePathPolicy.fileName("a/b/note.md"))
        assertEquals("note.md", TreePathPolicy.fileName("note.md"))
    }

    @Test
    fun `a nested tree gives the outer tree the longer chain`() {
        // Document docs/sub/deep/note.md, authorised twice: docs and docs/sub.
        val outermost = TreePathPolicy.relativePathWithinTree(
            "primary:docs/sub/deep/note.md",
            "primary:docs",
        )!!
        val nested = TreePathPolicy.relativePathWithinTree(
            "primary:docs/sub/deep/note.md",
            "primary:docs/sub",
        )!!
        val outermostChain = TreePathPolicy.directorySegments(outermost).size + 1
        val nestedChain = TreePathPolicy.directorySegments(nested).size + 1
        assertEquals(3, outermostChain)
        assertEquals(2, nestedChain)
        // The deeper chain belongs to the outer tree, which preferShallower must pick.
        assertTrue(TreePathPolicy.preferShallower(nestedChain, outermostChain))
        assertFalse(TreePathPolicy.preferShallower(outermostChain, nestedChain))
    }

    @Test
    fun `the first candidate wins when nothing is chosen yet`() {
        assertTrue(TreePathPolicy.preferShallower(null, 1))
        assertTrue(TreePathPolicy.preferShallower(null, 5))
    }

    @Test
    fun `equal chains keep the current choice so the result is deterministic`() {
        // Two unrelated trees can only both contain a document through the content-identity
        // fallback, and that path refuses ambiguity; equal depths must never flip the choice.
        assertFalse(TreePathPolicy.preferShallower(2, 2))
        assertFalse(TreePathPolicy.preferShallower(1, 1))
    }

    @Test
    fun `the outermost tree wins regardless of the order candidates are examined`() {
        // Child first (as the system may return it), parent second.
        var chosen = 2 // nested tree: docs/sub -> chain depth 2
        if (TreePathPolicy.preferShallower(chosen, 3)) chosen = 3
        assertEquals(3, chosen)
        // Parent first, child second.
        var chosen2 = 3
        if (TreePathPolicy.preferShallower(chosen2, 2)) {
            throw AssertionError("a nested tree must not replace the outer tree")
        }
        assertEquals(3, chosen2)
    }

    @Test
    fun `authorities are compared case insensitively and never guessed`() {
        assertTrue(TreePathPolicy.sameAuthority("com.android.externalstorage.documents", "com.android.externalstorage.documents"))
        assertTrue(TreePathPolicy.sameAuthority("com.Example.Documents", "com.example.documents"))
        assertFalse(TreePathPolicy.sameAuthority("com.android.externalstorage.documents", "com.android.providers.downloads.documents"))
        assertFalse(TreePathPolicy.sameAuthority(null, "com.example.documents"))
        assertFalse(TreePathPolicy.sameAuthority("com.example.documents", null))
        assertFalse(TreePathPolicy.sameAuthority("", ""))
    }
}
