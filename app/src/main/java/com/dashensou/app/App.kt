package com.dashensou.app

import android.app.Application
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dashensou.app.database.AppDatabase

class App : Application() {

    companion object {
        lateinit var instance: App
            private set
        lateinit var database: AppDatabase
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        database = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "dashensou_db"
        )
            .addMigrations(object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE download_records ADD COLUMN downloadId INTEGER NOT NULL DEFAULT -1")
                }
            })
            .build()
    }
}
