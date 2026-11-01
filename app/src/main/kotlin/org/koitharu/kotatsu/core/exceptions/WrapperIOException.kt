package org.zeravrix.kotatsu.core.exceptions

import okio.IOException

class WrapperIOException(override val cause: Exception) : IOException(cause)
