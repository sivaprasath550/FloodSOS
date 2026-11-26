package com.floodsos.data.local

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * v1 -> v2: added `smsSent`.
 * v2 -> v3: added `receivedAt`, `uploadedAt` and the `exchanges` table.
 * Room compares the exported schemas in app/schemas/ and generates the SQL for
 * us, so existing messages on users' phones are kept across updates.
 */
@Database(
    entities = [SosEntity::class, ExchangeEntity::class],
    version = 3,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sosDao(): SosDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "floodsos.db").build()
    }
}
