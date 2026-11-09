package com.storyweaver.source.literotica

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ContinuationPagesTest {
    private val page1 = "https://www.literotica.com/s/my-story"

    @Test
    fun `abbreviated pagination is filled in, in order, without page 1 or duplicates`() {
        val links = listOf(
            "$page1?page=2", // "Next" link appears first
            "$page1?page=1",
            "$page1?page=2",
            "$page1?page=3",
            "$page1?page=12", // "… 12"
            "$page1?page=2#comments",
        )
        val pages = LiteroticaParsers.continuationPageUrls(page1, links)
        assertThat(pages).hasSize(11)
        assertThat(pages.first()).isEqualTo("$page1?page=2")
        assertThat(pages[2]).isEqualTo("$page1?page=4") // gap filled
        assertThat(pages.last()).isEqualTo("$page1?page=12")
    }

    @Test
    fun `links to other stories, like the next chapter, are ignored`() {
        val links = listOf(
            "https://www.literotica.com/s/my-story-ch-02",
            "https://www.literotica.com/s/my-story-ch-02?page=2",
            "https://www.literotica.com/s/other?page=5",
        )
        assertThat(LiteroticaParsers.continuationPageUrls(page1, links)).isEmpty()
    }

    @Test
    fun `single page story has no continuations`() {
        assertThat(LiteroticaParsers.continuationPageUrls(page1, listOf(page1, "$page1?page=1"))).isEmpty()
    }

    @Test
    fun `path style page links are supported`() {
        val pages = LiteroticaParsers.continuationPageUrls(page1, listOf("$page1/2", "$page1/3"))
        assertThat(pages).containsExactly("$page1/2", "$page1/3").inOrder()
    }
}
