package com.floodsos.data.local

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * v1 -> v2: added `smsSent`. Room compares the exported schemas in app/schemas/
 * and generates the ALTER TABLE for us, so existing messages on users' phones are kept.
 */
@Database(
    entities = [SosEntity::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sosDao(): SosDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "floodsos.db").build()
    }
}
