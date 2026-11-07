package com.roomrentmanager.domain.usecase

import com.roomrentmanager.domain.finance.Money
import com.roomrentmanager.domain.model.Property
import com.roomrentmanager.domain.model.PropertyType
import com.roomrentmanager.domain.model.RentalUnit
import com.roomrentmanager.domain.model.ValidationException
import com.roomrentmanager.domain.repository.AuditRepository
import com.roomrentmanager.domain.repository.PropertyRepository
import com.roomrentmanager.domain.repository.TenancyRepository
import com.roomrentmanager.domain.repository.TransactionRunner

internal fun fail(message: String): Nothing = throw ValidationException(message)

internal fun String?.cleaned(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/** Properties and rooms: create, edit, archive. Nothing is ever deleted. */
class ManagePropertiesUseCase(
    private val tx: TransactionRunner,
    private val properties: PropertyRepository,
    private val tenancies: TenancyRepository,
    private val audit: AuditRepository,
) {
    suspend fun saveProperty(id: Long?, name: String, type: PropertyType, address: String?, notes: String?): Long = tx.inTransaction {
        val cleanName = name.cleaned() ?: fail("Enter a property name.")
        if (id == null) {
            val newId = properties.insertProperty(Property(0, cleanName, type, address.cleaned(), notes.cleaned(), true))
            audit.log("PROPERTY_CREATED", "PROPERTY", newId, "Created property '$cleanName' (${type.name.lowercase()}).")
            newId
        } else {
            val existing = properties.getProperty(id) ?: fail("This property no longer exists.")
            properties.updateProperty(existing.copy(name = cleanName, type = type, address = address.cleaned(), notes = notes.cleaned()))
            audit.log("PROPERTY_UPDATED", "PROPERTY", id, "Updated property '$cleanName'.")
            id
        }
    }

    suspend fun setPropertyActive(id: Long, active: Boolean) = tx.inTransaction {
        val existing = properties.getProperty(id) ?: fail("This property no longer exists.")
        if (!active && properties.countActiveRooms(id) > 0) fail("Archive this property's rooms first.")
        properties.updateProperty(existing.copy(isActive = active))
        audit.log(if (active) "PROPERTY_RESTORED" else "PROPERTY_ARCHIVED", "PROPERTY", id, "${if (active) "Restored" else "Archived"} property '${existing.name}'.")
    }

    suspend fun saveRoom(id: Long?, propertyId: Long, name: String, defaultRentPaise: Long?, notes: String?): Long = tx.inTransaction {
        val cleanName = name.cleaned() ?: fail("Enter a room or unit name.")
        if (defaultRentPaise != null && defaultRentPaise !in 0..Money.MAX_PAISE) fail("Enter a valid rent.")
        val property = properties.getProperty(propertyId) ?: fail("Choose a property.")
        if (id == null) {
            if (!property.isActive) fail("'${property.name}' is archived. Restore it before adding rooms.")
            val newId = properties.insertRoom(RentalUnit(0, propertyId, cleanName, defaultRentPaise, true, notes.cleaned()))
            audit.log("ROOM_CREATED", "ROOM", newId, "Created '$cleanName' in '${property.name}'.")
            newId
        } else {
            val existing = properties.getRoom(id) ?: fail("This room no longer exists.")
            if (existing.propertyId != propertyId && tenancies.activeTenancyForRoom(id) != null) {
                fail("End the current tenancy before moving this room to another property.")
            }
            properties.updateRoom(existing.copy(propertyId = propertyId, name = cleanName, defaultRentPaise = defaultRentPaise, notes = notes.cleaned()))
            audit.log("ROOM_UPDATED", "ROOM", id, "Updated '$cleanName'. Existing agreements and charges are unchanged.")
            id
        }
    }

    suspend fun setRoomActive(id: Long, active: Boolean) = tx.inTransaction {
        val room = properties.getRoom(id) ?: fail("This room no longer exists.")
        if (!active && tenancies.activeTenancyForRoom(id) != null) fail("End the current tenancy before archiving this room.")
        if (active && properties.getProperty(room.propertyId)?.isActive != true) fail("Restore the property first.")
        properties.updateRoom(room.copy(isActive = active))
        audit.log(if (active) "ROOM_RESTORED" else "ROOM_ARCHIVED", "ROOM", id, "${if (active) "Restored" else "Archived"} '${room.name}'.")
    }
}
