package com.bitnextechnologies.bitnexdial.di

import android.content.Context
import com.bitnextechnologies.bitnexdial.data.local.AppDatabase
import com.bitnextechnologies.bitnexdial.data.local.dao.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Qualifier for application-scoped CoroutineScope
 * Use this for long-running background operations that should survive configuration changes
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * Hilt module for app-wide dependencies
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Provides an application-scoped CoroutineScope for background operations.
     * This scope lives as long as the application and is properly managed by Hilt.
     * Uses SupervisorJob so individual child failures don't cancel the scope.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        return AppDatabase.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideContactDao(database: AppDatabase): ContactDao {
        return database.contactDao()
    }

    @Provides
    @Singleton
    fun provideCallDao(database: AppDatabase): CallDao {
        return database.callDao()
    }

    @Provides
    @Singleton
    fun provideMessageDao(database: AppDatabase): MessageDao {
        return database.messageDao()
    }

    @Provides
    @Singleton
    fun provideVoicemailDao(database: AppDatabase): VoicemailDao {
        return database.voicemailDao()
    }

    @Provides
    @Singleton
    fun provideUserDao(database: AppDatabase): UserDao {
        return database.userDao()
    }

    @Provides
    @Singleton
    fun providePhoneNumberDao(database: AppDatabase): PhoneNumberDao {
        return database.phoneNumberDao()
    }

    @Provides
    @Singleton
    fun provideBlockedNumberDao(database: AppDatabase): BlockedNumberDao {
        return database.blockedNumberDao()
    }
}
