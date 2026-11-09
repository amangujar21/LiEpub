package com.storyweaver.epub

import com.storyweaver.core.model.ExportMode

class EpubWriter {
    fun write(request: EpubWriteRequest): EpubWriteResult {
        val snapshot = request.snapshot
        val build = PackageAssembly.buildDownloaded(snapshot)
        return PackageAssembly.finalize(
            title = snapshot.title,
            creator = snapshot.curator ?: snapshot.authors.joinToString { it.displayName },
            language = request.language,
            identifier = request.identifier,
            build = build,
        )
    }
}

/** Convenience for multi-work separate mode */
fun EpubWriter.writeSeparate(request: EpubWriteRequest): List<EpubWriteResult> {
    val snapshot = request.snapshot
    return snapshot.selectedWorks.map { work ->
        val chapters = snapshot.selectedChapters.filter { it.workId == work.id }
        val contents = snapshot.chapterContents.filterKeys { id -> chapters.any { it.id == id } }
        val authors = snapshot.authors.filter { it.id == work.authorId }
        write(
            request.copy(
                snapshot = snapshot.copy(
                    title = work.title,
                    selectedWorks = listOf(work),
                    selectedChapters = chapters,
                    authors = authors,
                    chapterContents = contents,
                    exportMode = ExportMode.SEPARATE,
                ),
                identifier = "urn:uuid:${java.util.UUID.randomUUID()}",
            ),
        )
    }
}
