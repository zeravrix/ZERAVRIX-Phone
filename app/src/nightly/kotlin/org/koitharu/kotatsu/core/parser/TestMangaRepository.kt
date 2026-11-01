package org.zeravrix.kotatsu.core.parser

import org.zeravrix.kotatsu.core.cache.MemoryContentCache
import org.zeravrix.kotatsu.core.model.TestMangaSource
import org.koitharu.kotatsu.parsers.MangaLoaderContext

@Suppress("unused")
class TestMangaRepository(
	private val loaderContext: MangaLoaderContext,
	cache: MemoryContentCache
) : EmptyMangaRepository(TestMangaSource)
