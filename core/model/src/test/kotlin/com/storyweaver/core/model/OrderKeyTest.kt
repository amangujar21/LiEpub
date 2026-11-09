package com.storyweaver.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OrderKeyTest {
    @Test
    fun `parses decimal-looking labels without floating point`() {
        val a = OrderKey.parse("05.1")
        val b = OrderKey.parse("05.2")
        val c = OrderKey.parse("5")
        assertThat(a.segments).containsExactly(5, 1).inOrder()
        assertThat(a).isLessThan(b)
        assertThat(c).isLessThan(a)
    }

    @Test
    fun `sorts chapter labels naturally`() {
        val keys = listOf("Ch. 10", "Ch. 2", "Ch. 1", "05.1").map(OrderKey::parse)
        val sorted = keys.sorted()
        assertThat(sorted.map { it.toString() }).containsExactly("1", "2", "5.1", "10").inOrder()
    }
}
