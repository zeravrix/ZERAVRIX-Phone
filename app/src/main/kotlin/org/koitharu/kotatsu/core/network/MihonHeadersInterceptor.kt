package org.zeravrix.kotatsu.core.network

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.Interceptor
import okhttp3.Response
import org.zeravrix.kotatsu.core.parser.mihon.MihonMangaSource
import org.zeravrix.kotatsu.core.parser.mihon.MihonSourceRuntime
import org.koitharu.kotatsu.parsers.model.MangaSource
import javax.inject.Inject

/** Applies the loaded Mihon source's request headers to tagged reader/download calls. */
class MihonHeadersInterceptor @Inject constructor(
    @ApplicationContext private val context: Context,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val source = request.tag(MangaSource::class.java) as? MihonMangaSource
            ?: return chain.proceed(request)
        val headers = MihonSourceRuntime.requestHeaders(context, source, request)
            ?: return chain.proceed(request)
        return chain.proceed(
            request.newBuilder()
                .headers(headers)
                .build(),
        )
    }
}
