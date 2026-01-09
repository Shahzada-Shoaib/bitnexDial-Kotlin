package com.bitnextechnologies.bitnexdial.di

import android.content.Context
import android.webkit.CookieManager
import com.bitnextechnologies.bitnexdial.BuildConfig
import com.bitnextechnologies.bitnexdial.data.remote.api.BitnexApiService
import com.bitnextechnologies.bitnexdial.data.security.SecureCredentialManager
import com.bitnextechnologies.bitnexdial.data.remote.interceptor.RetryInterceptor
import com.bitnextechnologies.bitnexdial.data.remote.socket.SocketManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Cache
import okhttp3.CertificatePinner
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AuthInterceptor

/**
 * Hilt module for network dependencies
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideLoggingInterceptor(): HttpLoggingInterceptor {
        return HttpLoggingInterceptor().apply {
            // SECURITY: Only log headers in debug, never body (may contain credentials)
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.HEADERS
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
    }

    @Provides
    @Singleton
    @AuthInterceptor
    fun provideAuthInterceptor(
        @ApplicationContext context: Context,
        secureCredentialManager: SecureCredentialManager
    ): Interceptor {
        return Interceptor { chain ->
            // SECURITY: Read auth token from encrypted storage (not Room database)
            val token = secureCredentialManager.getAuthToken()

            val request = chain.request().newBuilder().apply {
                token?.let {
                    addHeader("Authorization", "Bearer $it")
                }
                addHeader("Content-Type", "application/json")
                addHeader("Accept", "application/json")
                addHeader("X-Platform", "android")
                addHeader("X-App-Version", BuildConfig.VERSION_NAME)
            }.build()

            // Log request
            val url = request.url.toString()
            android.util.Log.d("AuthInterceptor", "Request: ${request.method} $url")
            android.util.Log.d("AuthInterceptor", "Has auth token: ${token != null}")

            val response = chain.proceed(request)

            // Log response
            android.util.Log.d("AuthInterceptor", "Response: ${response.code} for $url")
            if (response.code == 401) {
                android.util.Log.e("AuthInterceptor", "401 Unauthorized! Check cookie/auth")
            }

            response
        }
    }

    @Provides
    @Singleton
    fun provideCookieJar(): CookieJar {
        return object : CookieJar {
            // Store cookies globally, not by host - OkHttp Cookie handles domain matching
            private val allCookies = mutableListOf<Cookie>()
            private val lock = Any()

            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                synchronized(lock) {
                    cookies.forEach { newCookie ->
                        // Remove existing cookies with same name and domain
                        allCookies.removeAll {
                            it.name == newCookie.name && it.domain == newCookie.domain
                        }
                        allCookies.add(newCookie)
                        android.util.Log.d("CookieJar", "Saved cookie: ${newCookie.name}=${newCookie.value.take(20)}... domain=${newCookie.domain} for ${url.host}")
                    }
                }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                synchronized(lock) {
                    val now = System.currentTimeMillis()
                    // Remove expired cookies
                    allCookies.removeAll { it.expiresAt < now }

                    // Return cookies that match this URL (OkHttp Cookie.matches handles domain matching)
                    val matchingCookies = allCookies.filter { cookie ->
                        cookie.matches(url)
                    }

                    if (matchingCookies.isNotEmpty()) {
                        android.util.Log.d("CookieJar", "Sending ${matchingCookies.size} cookies to ${url.host}: ${matchingCookies.map { it.name }}")
                    }

                    return matchingCookies
                }
            }
        }
    }

    @Provides
    @Singleton
    fun provideCache(@ApplicationContext context: Context): Cache {
        val cacheSize = 10L * 1024 * 1024 // 10 MB
        val cacheDir = File(context.cacheDir, "http_cache")
        return Cache(cacheDir, cacheSize)
    }

    @Provides
    @Singleton
    fun provideCertificatePinner(): CertificatePinner? {
        // SECURITY: Certificate pinning prevents MITM attacks
        // Disabled in debug builds to allow proxy debugging
        if (BuildConfig.DEBUG) {
            return null
        }

        // Certificate hashes generated from server's SSL certificates
        // Leaf certificate: valid Nov 30, 2025 - Feb 28, 2026 (Let's Encrypt, auto-renews)
        // Intermediate CA: pinned as backup for certificate renewal
        return CertificatePinner.Builder()
            // Leaf certificate hash
            .add("bkpmanual.bitnexdial.com", "sha256/+MFlUBD8yH/bJYlWW0Y92QESmXOtp6Jq3xgTU8/FPmI=")
            // Intermediate CA hash (backup for when leaf cert is renewed)
            .add("bkpmanual.bitnexdial.com", "sha256/kZwN96eHtZftBWrOZUsd6cA4es80n3NzSk/XtYz2EqQ=")
            .build()
    }

    @Provides
    @Singleton
    fun provideRetryInterceptor(): RetryInterceptor {
        return RetryInterceptor()
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        loggingInterceptor: HttpLoggingInterceptor,
        @AuthInterceptor authInterceptor: Interceptor,
        retryInterceptor: RetryInterceptor,
        cookieJar: CookieJar,
        cache: Cache,
        certificatePinner: CertificatePinner?
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .cache(cache)
            .cookieJar(cookieJar)
            .addInterceptor(authInterceptor)
            .addInterceptor(retryInterceptor)
            .addInterceptor(loggingInterceptor)
            .apply {
                // SECURITY: Enable certificate pinning in release builds
                certificatePinner?.let { certificatePinner(it) }
            }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL + "/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideBitnexApiService(retrofit: Retrofit): BitnexApiService {
        return retrofit.create(BitnexApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideSocketManager(): SocketManager {
        return SocketManager()
    }
}
