package com.storyweaver.source.literotica

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LiteroticaParsersTest {
    private fun fixture(name: String): String =
        javaClass.classLoader.getResourceAsStream("fixtures/$name")!!.bufferedReader().readText()

    @Test
    fun `parses author catalog into series and standalones`() {
        val catalog = LiteroticaParsers.parseAuthorCatalog(
            fixture("author-works.html"),
            "https://www.literotica.com/authors/shiprat/works/stories",
        )
        assertThat(catalog.author.siteUsername).isEqualTo("shiprat")
        assertThat(catalog.series).hasSize(1)
        assertThat(catalog.series.first().chapters).hasSize(3)
        assertThat(catalog.standalones).hasSize(1)
        assertThat(catalog.series.first().chapters.map { it.orderKey.toString() })
            .containsExactly("1", "2", "3")
            .inOrder()
    }

    @Test
    fun `parses modern author works CSS modules series without duplicating chapters as standalones`() {
        // Live site: series title card + sibling _parts rows; part cards have no /series/se/ link.
        val catalog = LiteroticaParsers.parseAuthorCatalog(
            fixture("author-works-modern.html"),
            "https://www.literotica.com/authors/shiprat/works/stories",
        )
        assertThat(catalog.series).hasSize(1)
        assertThat(catalog.series.first().work.title).isEqualTo("Harbor Nights")
        assertThat(catalog.series.first().chapters).hasSize(3)
        assertThat(catalog.series.first().chapters.map { it.canonicalUrl }).containsExactly(
            "https://www.literotica.com/s/harbor-nights-ch-01",
            "https://www.literotica.com/s/harbor-nights-ch-02",
            "https://www.literotica.com/s/harbor-nights-ch-03",
        ).inOrder()
        assertThat(catalog.standalones).hasSize(1)
        assertThat(catalog.standalones.single().work.title).isEqualTo("One Off Story")
        val standaloneUrls = catalog.standalones.map { it.chapter.canonicalUrl }.toSet()
        catalog.series.flatMap { it.chapters }.forEach { ch ->
            assertThat(standaloneUrls).doesNotContain(ch.canonicalUrl)
        }
    }

    @Test
    fun `parses series page ordered chapters`() {
        val series = LiteroticaParsers.parseSeriesPage(
            fixture("series.html"),
            "https://www.literotica.com/series/se/12345",
        )
        assertThat(series.work.title).isEqualTo("Harbor Nights")
        assertThat(series.chapters).hasSize(3)
    }

    @Test
    fun `parses story and sanitizes body`() {
        val story = LiteroticaParsers.parseStoryPage(
            fixture("story.html"),
            "https://www.literotica.com/s/harbor-nights-ch-01",
        )
        assertThat(story.chapter.title).contains("Harbor Nights")
        assertThat(story.seriesUrl).contains("/series/se/12345")
        assertThat(story.content.xhtmlBody).contains("harbor lights")
        assertThat(story.content.xhtmlBody).doesNotContain("<script")
        assertThat(story.content.xhtmlBody).doesNotContain("<img")
    }

    @Test
    fun `modern story page extracts prose body not Story Info chrome`() {
        // Live markup puts metadata in article .panel before [itemprop=articleBody].
        val story = LiteroticaParsers.parseStoryPage(
            fixture("story-modern.html"),
            "https://www.literotica.com/s/harbor-nights-ch-01",
        )
        assertThat(story.content.xhtmlBody).contains("harbor lights shimmered")
        assertThat(story.content.xhtmlBody).contains("walked the pier alone")
        assertThat(story.content.xhtmlBody.lowercase()).doesNotContain("story info")
        assertThat(story.content.xhtmlBody).doesNotContain("5.7k words")
        assertThat(story.content.xhtmlBody).doesNotContain("1,198 Followers")
        assertThat(story.content.xhtmlBody).doesNotContain("indiaindianslutwife")
    }

    private val breadcrumb = """
        <nav><a href="https://www.literotica.com/">Home</a></nav>
        <ol itemscope itemtype="https://schema.org/BreadcrumbList">
          <li itemprop="itemListElement" itemscope itemtype="https://schema.org/ListItem">
            <a href="/"><span itemprop="name"><span class="visually-hidden">Home</span></span></a>
          </li>
          <li itemprop="itemListElement" itemscope itemtype="https://schema.org/ListItem">
            <a href="/authors/shiprat"><span itemprop="name">shiprat</span></a>
          </li>
        </ol>
    """.trimIndent()

    @Test
    fun `live breadcrumb Home label never becomes author or title`() {
        val works = LiteroticaParsers.parseAuthorCatalog(
            """<html><body>$breadcrumb<h1> Stories by shiprat</h1>
               <a href="https://www.literotica.com/s/a-tale">A Tale</a></body></html>""",
            "https://www.literotica.com/authors/shiprat/works/stories",
        )
        assertThat(works.author.displayName).isEqualTo("shiprat")

        val series = LiteroticaParsers.parseSeriesPage(
            """<html><body>$breadcrumb<h1>Harbor Nights</h1>
               <a href="https://www.literotica.com/authors/shiprat/works/stories" title="shiprat"><img alt=""/></a>
               <a href="https://www.literotica.com/authors/shiprat/works/stories">29 Stories</a>
               <a href="https://www.literotica.com/s/harbor-nights-ch-01">Harbor Nights Ch. 01</a></body></html>""",
            "https://www.literotica.com/series/se/123",
        )
        assertThat(series.work.title).isEqualTo("Harbor Nights")

        val story = LiteroticaParsers.parseStoryPage(
            """<html><body>$breadcrumb<h1>A Tale</h1>
               <a href="https://www.literotica.com/authors/shiprat">shiprat</a>
               <div class="aa_ht"><p>${"Body text. ".repeat(20)}</p></div></body></html>""",
            "https://www.literotica.com/s/a-tale",
        )
        assertThat(story.chapter.title).isEqualTo("A Tale")
        assertThat(story.author.displayName).isEqualTo("shiprat")
    }
}
