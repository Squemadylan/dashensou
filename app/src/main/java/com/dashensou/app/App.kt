package com.dashensou.app

import android.app.Application
import android.util.Log
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dashensou.app.database.AppDatabase
import com.dashensou.app.service.DownloadManager
import com.dashensou.app.service.DownloadProgressPoller

class App : Application() {

    companion object {
        lateinit var instance: App
            private set
        lateinit var database: AppDatabase
            private set

        // P1#20: a single versioned Migration table replaces the
        // older "register one ad-hoc Migration in App.kt" pattern.
        // New schema bumps add an entry here rather than patching
        // the databaseBuilder call in onCreate. The constants also
        // make "what's the current schema version?" answerable by
        // grep.
        private const val TAG = "AppDatabase"
        const val DB_VERSION: Int = 2
        const val DB_NAME: String = "dashensou_db"

        private val MIGRATIONS: Array<Migration> = arrayOf(
            // v1 -> v2: introduce downloadId column to track
            // Android DownloadManager ids. Older rows get -1 which
            // is already DownloadViewModel's "no system id" sentinel.
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

        // P0#robustness: DownloadManager is a process-wide singleton.
        // Initialising it from Application.onCreate guarantees the
        // BroadcastReceiver and the long-lived coroutine scope are
        // wired up exactly once. MainActivity no longer constructs
        // it (it used to, and that caused receiver leaks on rotation).
        DownloadManager.init(this)

        database = Room.databaseBuilder(applicationContext, AppDatabase::class.java, DB_NAME)
            .addMigrations(*MIGRATIONS)
            // Safety net: if a future schema bump ships without a
            // migration path (e.g. local dev), recreate the DB
            // rather than crash on startup. Production releases
            // must still ship a Migration for every version bump —
            // see comment on [MIGRATIONS].
            .fallbackToDestructiveMigration()
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    Log.i(TAG, "database opened (version=${db.version})")
                }
            })
            .build()

        // P0 fix: process-wide download progress poller. The old
        // version of this logic lived in MainActivity, gated on
        // currentTab == TAB_DOWNLOADS, so progress froze as soon as
        // the user navigated away. UI now just collects the Flow.
        DownloadProgressPoller.start(this)
    }
}
