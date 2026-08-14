package com.dashensou.app

import android.app.Application
import android.util.Log
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dashensou.app.database.AppDatabase
import com.dashensou.app.service.DownloadManager
import com.dashensou.app.service.DownloadProgressPoller
import com.dashensou.app.service.SearchService
import com.dashensou.app.web.AppWebView

class App : Application() {

    companion object {
        lateinit var instance: App
            private set
        lateinit var database: AppDatabase
            private set
        lateinit var searchService: SearchService

        private const val TAG = "AppDatabase"
        const val PREFS_NAME: String = "dashensou_prefs"
        const val DB_VERSION: Int = 2
        const val DB_NAME: String = "dashensou_db"

        private val MIGRATIONS: Array<Migration> = arrayOf(
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE download_records ADD COLUMN downloadId INTEGER NOT NULL DEFAULT -1"
                    )
                }
            }
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // P0#robustness: process-wide singleton. Created here and
        // survives Activity config changes (theme switch, rotation).
        // Source enabled states are loaded from SharedPreferences
        // inside SearchService's init so they persist across any
        // Activity lifecycle event — no more "theme switch resets
        // sources".
        searchService = SearchService(context = this)

        // WebView singleton for HTML-anti-scraping sources (pansou_cc /
        // haisou / aiqu225). Long-lived; serialized via internal Mutex.
        // Must be initialized before any of those sources run their first
        // search — guaranteed because SearchService only invokes sources
        // on demand from user input.
        AppWebView.init(this)

        DownloadManager.init(this)

        database = Room.databaseBuilder(applicationContext, AppDatabase::class.java, DB_NAME)
            .addMigrations(*MIGRATIONS)
            .fallbackToDestructiveMigration()
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    Log.i(TAG, "database opened (version=${db.version})")
                }
            })
            .build()

        DownloadProgressPoller.start(this)
    }
}
