package org.zeravrix.kotatsu.core.exceptions

class SyncApiException(
	message: String,
	val code: Int,
) : RuntimeException(message)
