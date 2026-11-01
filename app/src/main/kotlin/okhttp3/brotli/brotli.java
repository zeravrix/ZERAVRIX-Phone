package okhttp3.brotli;

import okhttp3.Interceptor;
import okhttp3.Response;
import java.io.IOException;

/**
 * Binary compatibility bridge for extensions referencing Lokhttp3/brotli/brotli;
 */
public class brotli implements Interceptor {
    public static final brotli INSTANCE = new brotli();

    @Override
    public Response intercept(Chain chain) throws IOException {
        return BrotliInterceptor.INSTANCE.intercept(chain);
    }
}
