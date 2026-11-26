package com.floodsos.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.floodsos.core.HeldMessage
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
    // v2. The default lets Room's auto-migration fill existing rows.
    @ColumnInfo(defaultValue = "0") val smsSent: Boolean = false,
    // v3 (field-test stats). 0 = unknown, for rows saved before v3.
    @ColumnInfo(defaultValue = "0") val receivedAt: Long = 0,
    val uploadedAt: Long? = null,
)

fun SosEntity.toDomain() = SosMessage(id, senderId, type, lat, lng, note, createdAt, hops, uploaded, smsSent)

fun SosEntity.toHeld() = HeldMessage(toDomain(), receivedAt, uploadedAt)

fun SosMessage.toEntity(receivedAt: Long) =
    SosEntity(id, senderId, type, lat, lng, note, createdAt, hops, uploaded, smsSent, receivedAt)
