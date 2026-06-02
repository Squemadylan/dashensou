package com.dashensou.app

import android.app.Application
import androidx.room.Room
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
        ).build()
    }
}
