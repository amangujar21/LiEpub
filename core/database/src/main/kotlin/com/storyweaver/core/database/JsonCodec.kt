package com.storyweaver.core.database

import com.storyweaver.core.model.Author
import com.storyweaver.core.model.AuthorId
import com.storyweaver.core.model.Chapter
import com.storyweaver.core.model.ChapterId
import com.storyweaver.core.model.OrderKey
import com.storyweaver.core.model.TocNode
import com.storyweaver.core.model.TocNodeId
import com.storyweaver.core.model.TocNodeKind
import com.storyweaver.core.model.Work
import com.storyweaver.core.model.WorkId
import com.storyweaver.core.model.WorkType
import org.json.JSONArray
import org.json.JSONObject

/** Minimal JSON codec for Room snapshots (no kotlinx.serialization dependency). */
object JsonCodec {
    fun authorsToJson(authors: List<Author>): String = JSONArray().apply {
        authors.forEach { a ->
            put(
                JSONObject()
                    .put("id", a.id.value)
                    .put("siteUsername", a.siteUsername)
                    .put("displayName", a.displayName)
                    .put("worksUrl", a.worksUrl),
            )
        }
    }.toString()

    fun authorsFromJson(json: String): List<Author> {
        if (json.isBlank()) return emptyList()
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Author(
                id = AuthorId(o.getString("id")),
                siteUsername = o.getString("siteUsername"),
                displayName = o.getString("displayName"),
                worksUrl = o.optString("worksUrl", ""),
            )
        }
    }

    fun worksToJson(works: List<Work>): String = JSONArray().apply {
        works.forEach { w ->
            put(
                JSONObject()
                    .put("id", w.id.value)
                    .put("authorId", w.authorId.value)
                    .put("title", w.title)
                    .put("description", w.description)
                    .put("category", w.category)
                    .put("publicationDate", w.publicationDate)
                    .put("canonicalUrl", w.canonicalUrl)
                    .put("type", w.type.name),
            )
        }
    }.toString()

    fun worksFromJson(json: String): List<Work> {
        if (json.isBlank()) return emptyList()
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Work(
                id = WorkId(o.getString("id")),
                authorId = AuthorId(o.getString("authorId")),
                title = o.getString("title"),
                description = o.nullableString("description"),
                category = o.nullableString("category"),
                publicationDate = o.nullableString("publicationDate"),
                canonicalUrl = o.getString("canonicalUrl"),
                type = WorkType.valueOf(o.getString("type")),
            )
        }
    }

    fun chaptersToJson(chapters: List<Chapter>): String = JSONArray().apply {
        chapters.forEach { c ->
            put(
                JSONObject()
                    .put("id", c.id.value)
                    .put("workId", c.workId.value)
                    .put("sitePartId", c.sitePartId)
                    .put("title", c.title)
                    .put("orderKey", JSONArray(c.orderKey.segments))
                    .put("canonicalUrl", c.canonicalUrl)
                    .put("publicationDate", c.publicationDate)
                    .put("revisionFingerprint", c.revisionFingerprint),
            )
        }
    }.toString()

    fun chaptersFromJson(json: String): List<Chapter> {
        if (json.isBlank()) return emptyList()
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val segs = o.getJSONArray("orderKey")
            val segments = (0 until segs.length()).map { segs.getInt(it) }
            Chapter(
                id = ChapterId(o.getString("id")),
                workId = WorkId(o.getString("workId")),
                sitePartId = o.nullableString("sitePartId"),
                title = o.getString("title"),
                orderKey = OrderKey(segments),
                canonicalUrl = o.getString("canonicalUrl"),
                publicationDate = o.nullableString("publicationDate"),
                revisionFingerprint = o.nullableString("revisionFingerprint"),
            )
        }
    }

    fun nodesToJson(nodes: List<TocNode>): String = JSONArray().apply {
        nodes.forEach { n ->
            put(
                JSONObject()
                    .put("id", n.id.value)
                    .put("parentId", n.parentId?.value)
                    .put("kind", n.kind.name)
                    .put("referencedEntityId", n.referencedEntityId)
                    .put("displayTitle", n.displayTitle)
                    .put("position", n.position)
                    .put("selected", n.selected)
                    .put("lockedChildren", n.lockedChildren),
            )
        }
    }.toString()

    fun nodesFromJson(json: String): List<TocNode> {
        if (json.isBlank()) return emptyList()
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            TocNode(
                id = TocNodeId(o.getString("id")),
                parentId = o.nullableString("parentId")?.let { TocNodeId(it) },
                kind = TocNodeKind.valueOf(o.getString("kind")),
                referencedEntityId = o.getString("referencedEntityId"),
                displayTitle = o.getString("displayTitle"),
                position = o.getInt("position"),
                selected = o.getBoolean("selected"),
                lockedChildren = o.optBoolean("lockedChildren", false),
            )
        }
    }

    fun stringListToJson(list: List<String>): String = JSONArray(list).toString()

    fun stringListFromJson(json: String): List<String> {
        if (json.isBlank()) return emptyList()
        val arr = JSONArray(json)
        return (0 until arr.length()).map { arr.getString(it) }
    }
}

private fun JSONObject.nullableString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val v = getString(key)
    return v.ifBlank { null }
}
