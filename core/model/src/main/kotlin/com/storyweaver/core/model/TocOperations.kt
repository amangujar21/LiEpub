package com.storyweaver.core.model

object TocOperations {
    /** Thin wrapper over [buildFromResolved] for single-catalog sources. */
    fun buildFromCatalog(
        collectionId: CollectionId,
        catalog: AuthorCatalog,
        selectedWorkIds: Set<WorkId>,
    ): CollectionDraft {
        val works = catalog.series.map { it.work } + catalog.standalones.map { it.work }
        val chapters = catalog.series.flatMap { it.chapters } + catalog.standalones.map { it.chapter }
        return buildFromResolved(
            collectionId = collectionId,
            authors = listOf(catalog.author),
            works = works,
            chapters = chapters,
            selectedWorkIds = selectedWorkIds,
        )
    }

    /**
     * Chapter IDs selected for export. Standalones resolve through real [Chapter] rows
     * (by work id), never via synthetic `chapter-for-…` ids.
     */
    fun selectedChapterIds(draft: CollectionDraft, chapters: List<Chapter>): Set<ChapterId> {
        val seriesChapterIds = draft.nodes
            .filter { it.kind == TocNodeKind.CHAPTER && it.selected }
            .map { ChapterId(it.referencedEntityId) }
            .toSet()
        val selectedStandaloneWorkIds = draft.nodes
            .filter { it.kind == TocNodeKind.STANDALONE && it.selected }
            .map { WorkId(it.referencedEntityId) }
            .toSet()
        val standaloneChapterIds = chapters
            .filter { it.workId in selectedStandaloneWorkIds }
            .map { it.id }
            .toSet()
        return seriesChapterIds + standaloneChapterIds
    }

    fun validateHierarchy(nodes: List<TocNode>): List<String> {
        val errors = mutableListOf<String>()
        val byId = nodes.associateBy { it.id }

        nodes.forEach { node ->
            val parent = node.parentId?.let { byId[it] }
            if (node.parentId != null && parent == null) {
                errors += "Node ${node.id.value} references missing parent ${node.parentId.value}"
            }
            when (node.kind) {
                TocNodeKind.AUTHOR -> if (node.parentId != null) {
                    errors += "Author node must be root: ${node.id.value}"
                }
                TocNodeKind.SERIES, TocNodeKind.STANDALONE -> if (parent?.kind != TocNodeKind.AUTHOR) {
                    errors += "Work node must hang under author: ${node.id.value}"
                }
                TocNodeKind.CHAPTER -> if (parent?.kind != TocNodeKind.SERIES) {
                    errors += "Chapter must hang under series: ${node.id.value}"
                }
                TocNodeKind.IMPORTED_BOOK -> if (node.parentId != null) {
                    errors += "Imported book should be top-level in v1: ${node.id.value}"
                }
                TocNodeKind.IMPORTED_SECTION -> if (parent?.kind != TocNodeKind.IMPORTED_BOOK &&
                    parent?.kind != TocNodeKind.IMPORTED_SECTION
                ) {
                    errors += "Imported section hierarchy invalid: ${node.id.value}"
                }
            }
        }

        // Cycle detection via parent walk
        nodes.forEach { start ->
            val seen = mutableSetOf<TocNodeId>()
            var current: TocNode? = start
            while (current != null) {
                if (!seen.add(current.id)) {
                    errors += "Cycle detected at ${start.id.value}"
                    break
                }
                current = current.parentId?.let { byId[it] }
            }
        }

        return errors
    }

    fun toExportSnapshot(
        draft: CollectionDraft,
        authors: List<Author>,
        works: List<Work>,
        chapters: List<Chapter>,
        contents: Map<ChapterId, ChapterContent>,
    ): ExportSnapshot {
        val selectedSeriesWorkIds = draft.nodes
            .filter { it.kind == TocNodeKind.SERIES && it.selected }
            .map { WorkId(it.referencedEntityId) }
            .toSet()
        val selectedStandaloneIds = draft.nodes
            .filter { it.kind == TocNodeKind.STANDALONE && it.selected }
            .map { WorkId(it.referencedEntityId) }
            .toSet()
        // Work export order follows draft TOC positions (authors, then works under each).
        val workOrder = workOrderFromDraft(draft)
        val selectedWorks = workOrder
            .mapNotNull { id -> works.find { it.id == id } }
            .filter { it.id in selectedSeriesWorkIds || it.id in selectedStandaloneIds }
            .ifEmpty {
                works.filter { it.id in selectedSeriesWorkIds || it.id in selectedStandaloneIds }
            }
        val workRank = selectedWorks.mapIndexed { index, w -> w.id to index }.toMap()
        val selectedChapterIds = draft.nodes
            .filter { it.kind == TocNodeKind.CHAPTER && it.selected }
            .map { ChapterId(it.referencedEntityId) }
            .toSet()
        val standaloneChapterIds = chapters
            .filter { it.workId in selectedStandaloneIds }
            .map { it.id }
            .toSet()
        val selectedChapters = chapters.filter {
            it.id in selectedChapterIds || it.id in standaloneChapterIds
        }.sortedWith(
            compareBy(
                { workRank[it.workId] ?: Int.MAX_VALUE },
                { it.orderKey },
            ),
        )

        return ExportSnapshot(
            collectionId = draft.id,
            title = draft.title,
            subtitle = draft.subtitle,
            curator = draft.curator,
            exportMode = draft.exportMode,
            epubStyle = draft.epubStyle,
            accentColorArgb = draft.accentColorArgb,
            selectedChapters = selectedChapters,
            selectedWorks = selectedWorks,
            authors = authors,
            chapterContents = contents.filterKeys { id -> selectedChapters.any { it.id == id } },
        )
    }

    /** Flatten work (series/standalone) order from draft nodes by author position then work position. */
    fun workOrderFromDraft(draft: CollectionDraft): List<WorkId> {
        val authors = draft.nodes
            .filter { it.kind == TocNodeKind.AUTHOR }
            .sortedBy { it.position }
        val result = mutableListOf<WorkId>()
        authors.forEach { author ->
            draft.nodes
                .filter {
                    it.parentId == author.id &&
                        (it.kind == TocNodeKind.SERIES || it.kind == TocNodeKind.STANDALONE)
                }
                .sortedBy { it.position }
                .forEach { result += WorkId(it.referencedEntityId) }
        }
        // Orphan works (no author node) still contribute.
        draft.nodes
            .filter {
                (it.kind == TocNodeKind.SERIES || it.kind == TocNodeKind.STANDALONE) &&
                    WorkId(it.referencedEntityId) !in result
            }
            .sortedBy { it.position }
            .forEach { result += WorkId(it.referencedEntityId) }
        return result
    }

    /** Move a node among its siblings by delta positions (-1 up, +1 down). Locked children stay fixed. */
    fun reorderSibling(nodes: List<TocNode>, nodeId: TocNodeId, delta: Int): List<TocNode> {
        if (delta == 0) return nodes
        val target = nodes.firstOrNull { it.id == nodeId } ?: return nodes
        if (target.lockedChildren && target.kind == TocNodeKind.IMPORTED_SECTION) return nodes
        val parent = target.parentId
        val siblings = nodes.filter { it.parentId == parent }.sortedBy { it.position }
        val index = siblings.indexOfFirst { it.id == nodeId }
        if (index < 0) return nodes
        val newIndex = (index + delta).coerceIn(0, siblings.lastIndex)
        if (newIndex == index) return nodes
        val reordered = siblings.toMutableList()
        val moved = reordered.removeAt(index)
        reordered.add(newIndex, moved)
        val positionMap = reordered.mapIndexed { i, n -> n.id to i }.toMap()
        return nodes.map { n ->
            if (n.parentId == parent) n.copy(position = positionMap[n.id] ?: n.position) else n
        }
    }

    fun setSelected(nodes: List<TocNode>, nodeId: TocNodeId, selected: Boolean): List<TocNode> {
        val target = nodes.firstOrNull { it.id == nodeId } ?: return nodes
        val children = nodes.groupBy { it.parentId }
        val toUpdate = mutableSetOf(nodeId)
        fun collect(id: TocNodeId) {
            children[id].orEmpty().forEach {
                toUpdate += it.id
                collect(it.id)
            }
        }
        collect(nodeId)
        // Selecting a series selects chapters; deselecting cascades too.
        return nodes.map { n ->
            if (n.id in toUpdate) n.copy(selected = selected) else n
        }.let { updated ->
            // If a chapter is selected, ensure parent series/author stay selected.
            if (selected) {
                val byId = updated.associateBy { it.id }
                val parents = mutableSetOf<TocNodeId>()
                var cur: TocNode? = target
                while (cur?.parentId != null) {
                    parents += cur.parentId!!
                    cur = byId[cur.parentId]
                }
                updated.map { if (it.id in parents) it.copy(selected = true) else it }
            } else {
                updated
            }
        }
    }

    fun renameNode(nodes: List<TocNode>, nodeId: TocNodeId, title: String): List<TocNode> =
        nodes.map { if (it.id == nodeId) it.copy(displayTitle = title.trim().ifBlank { it.displayTitle }) else it }

    fun mergeCatalogs(catalogs: List<AuthorCatalog>): AuthorCatalog {
        require(catalogs.isNotEmpty()) { "Need at least one catalog" }
        if (catalogs.size == 1) return catalogs.first()
        val primary = catalogs.first()
        val seriesById = linkedMapOf<WorkId, CatalogSeries>()
        val standalonesById = linkedMapOf<WorkId, CatalogStandalone>()
        val warnings = mutableListOf<ParseWarning>()
        catalogs.forEach { cat ->
            warnings += cat.warnings
            cat.series.forEach { s ->
                val existing = seriesById[s.work.id]
                if (existing == null) {
                    seriesById[s.work.id] = s
                } else {
                    val chapters = (existing.chapters + s.chapters)
                        .distinctBy { it.canonicalUrl }
                        .sortedBy { it.orderKey }
                    seriesById[s.work.id] = existing.copy(chapters = chapters)
                }
            }
            cat.standalones.forEach { st ->
                standalonesById.putIfAbsent(st.work.id, st)
            }
        }
        return AuthorCatalog(
            author = primary.author,
            series = seriesById.values.toList(),
            standalones = standalonesById.values.toList(),
            warnings = warnings.distinctBy { it.code + (it.url ?: "") + it.message },
        )
    }

    fun mergeResolvedDrafts(drafts: List<ResolvedDraft>): ResolvedDraft {
        if (drafts.isEmpty()) {
            return ResolvedDraft(emptyList(), emptyList(), emptyList())
        }
        val authors = drafts.flatMap { it.authors }.distinctBy { it.id }
        val works = drafts.flatMap { it.works }.distinctBy { it.id }
        val chapters = drafts.flatMap { it.chapters }.distinctBy { it.canonicalUrl }
        val warnings = drafts.flatMap { it.warnings }.distinctBy { it.code + (it.url ?: "") + it.message }
        return ResolvedDraft(authors, works, chapters, warnings)
    }

    fun buildFromResolved(
        collectionId: CollectionId,
        authors: List<Author>,
        works: List<Work>,
        chapters: List<Chapter>,
        selectedWorkIds: Set<WorkId>,
        title: String? = null,
        curator: String? = null,
        workOrder: List<WorkId> = emptyList(),
        deselectedChapterIds: Set<ChapterId> = emptySet(),
        accentColorArgb: Long = 0xFF7A2E3D,
    ): CollectionDraft {
        val nodes = mutableListOf<TocNode>()
        val orderIndex = workOrder.mapIndexed { i, id -> id to i }.toMap()
        val selectedWorks = works
            .filter { it.id in selectedWorkIds }
            .sortedWith(
                compareBy(
                    { orderIndex[it.id] ?: Int.MAX_VALUE },
                    { it.title.lowercase() },
                ),
            )
        val authorsInUse = authors.filter { a -> selectedWorks.any { it.authorId == a.id } }
            .ifEmpty { authors }
        var authorPos = 0
        authorsInUse.forEach { author ->
            val authorNodeId = TocNodeId("author-${author.id.value}")
            nodes += TocNode(
                id = authorNodeId,
                parentId = null,
                kind = TocNodeKind.AUTHOR,
                referencedEntityId = author.id.value,
                displayTitle = author.displayName,
                position = authorPos++,
                selected = true,
            )
            var workPos = 0
            selectedWorks.filter { it.authorId == author.id }.forEach { work ->
                when (work.type) {
                    WorkType.SERIES -> {
                        val seriesNodeId = TocNodeId("series-${work.id.value}")
                        nodes += TocNode(
                            id = seriesNodeId,
                            parentId = authorNodeId,
                            kind = TocNodeKind.SERIES,
                            referencedEntityId = work.id.value,
                            displayTitle = work.title,
                            position = workPos++,
                            selected = true,
                        )
                        chapters.filter { it.workId == work.id }
                            .sortedBy { it.orderKey }
                            .forEachIndexed { index, chapter ->
                                nodes += TocNode(
                                    id = TocNodeId("chapter-${chapter.id.value}"),
                                    parentId = seriesNodeId,
                                    kind = TocNodeKind.CHAPTER,
                                    referencedEntityId = chapter.id.value,
                                    displayTitle = chapter.title,
                                    position = index,
                                    selected = chapter.id !in deselectedChapterIds,
                                )
                            }
                    }
                    WorkType.STANDALONE -> {
                        nodes += TocNode(
                            id = TocNodeId("standalone-${work.id.value}"),
                            parentId = authorNodeId,
                            kind = TocNodeKind.STANDALONE,
                            referencedEntityId = work.id.value,
                            displayTitle = work.title,
                            position = workPos++,
                            selected = true,
                        )
                    }
                }
            }
        }
        val defaultTitle = title ?: when {
            selectedWorks.size == 1 -> selectedWorks.first().title
            authorsInUse.size == 1 -> "Selected Stories — ${authorsInUse.first().displayName}"
            authorsInUse.isNotEmpty() -> "Collected Stories — ${authorsInUse.size} Authors"
            else -> "Selected Stories"
        }
        return CollectionDraft(
            id = collectionId,
            title = defaultTitle,
            curator = curator ?: authorsInUse.joinToString { it.displayName }.ifBlank { null },
            accentColorArgb = accentColorArgb,
            nodes = nodes,
        )
    }

    fun appendImportedBook(
        draft: CollectionDraft,
        book: ImportedBook,
        sectionTitles: List<String> = emptyList(),
    ): CollectionDraft {
        val bookNodeId = TocNodeId("imported-${book.id.value}")
        val position = draft.nodes.filter { it.parentId == null }.maxOfOrNull { it.position + 1 } ?: 0
        val newNodes = mutableListOf(
            TocNode(
                id = bookNodeId,
                parentId = null,
                kind = TocNodeKind.IMPORTED_BOOK,
                referencedEntityId = book.id.value,
                displayTitle = book.title,
                position = position,
                selected = true,
                lockedChildren = true,
            ),
        )
        sectionTitles.forEachIndexed { index, title ->
            newNodes += TocNode(
                id = TocNodeId("imported-sec-${book.id.value}-$index"),
                parentId = bookNodeId,
                kind = TocNodeKind.IMPORTED_SECTION,
                referencedEntityId = "${book.id.value}:$index",
                displayTitle = title,
                position = index,
                selected = true,
                lockedChildren = true,
            )
        }
        return draft.copy(nodes = draft.nodes + newNodes)
    }
}
