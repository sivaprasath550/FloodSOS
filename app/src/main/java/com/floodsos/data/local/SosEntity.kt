package com.floodsos.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.floodsos.core.SosMessage

/**
 * The database row. Kept separate from [SosMessage] so the core model has no
 * Room/Android dependency. Mapping functions below convert between the two.
 */
@Entity(tableName = "messages")
data class SosEntity(
    @PrimaryKey val id: String, // PRIMARY KEY = the database enforces "one row per message id"
    val senderId: String,
    val type: String,
    val lat: Double,
    val lng: Double,
    val note: String,
    val createdAt: Long,
    val hops: Int,
    val uploaded: Boolean,
    // Added in DB version 2. The default lets Room's auto-migration fill existing rows.
    @ColumnInfo(defaultValue = "0") val smsSent: Boolean = false,
)

fun SosEntity.toDomain() = SosMessage(id, senderId, type, lat, lng, note, createdAt, hops, uploaded, smsSent)

fun SosMessage.toEntity() = SosEntity(id, senderId, type, lat, lng, note, createdAt, hops, uploaded, smsSent)
