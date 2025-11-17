package com.kaj.studioreserve.equipment

import com.kaj.studioreserve.studios.StudiosTable
import com.kaj.studioreserve.users.UserRole
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

class EquipmentService {
    fun createEquipment(ownerId: UUID, studioId: UUID, request: CreateEquipmentRequest): EquipmentCreateResult =
        transaction {
            val studioRow = StudiosTable.select { StudiosTable.id eq studioId }.singleOrNull()
                ?: return@transaction EquipmentCreateResult.StudioNotFound

            if (studioRow[StudiosTable.ownerId] != ownerId) {
                return@transaction EquipmentCreateResult.Forbidden
            }

            val equipmentId = UUID.randomUUID()
            val barcodeCode = UUID.randomUUID().toString()

            EquipmentTable.insert { statement ->
                statement[EquipmentTable.id] = equipmentId
                statement[EquipmentTable.studioId] = studioId
                statement[EquipmentTable.name] = request.name.trim()
                statement[EquipmentTable.brand] = request.brand.trim()
                statement[EquipmentTable.type] = request.type.trim()
                statement[EquipmentTable.rentalPrice] = request.rentalPrice.toBigDecimalWithScale()
                statement[EquipmentTable.condition] = request.condition.trim()
                statement[EquipmentTable.serialNumber] = request.serialNumber.trim()
                statement[EquipmentTable.status] = EquipmentStatus.AVAILABLE
                statement[EquipmentTable.barcodeCode] = barcodeCode
                statement[EquipmentTable.barcodeImageUrl] = null
            }

            val equipmentRow = EquipmentTable.select { EquipmentTable.id eq equipmentId }.single()
            EquipmentCreateResult.Success(equipmentRow.toEquipmentDto())
        }

    fun listEquipment(role: UserRole, ownerId: UUID?, studioId: UUID?): List<EquipmentDto> = transaction {
        when (role) {
            UserRole.ADMIN -> {
                val query = EquipmentTable.selectAll()
                studioId?.let { filter ->
                    query.andWhere { EquipmentTable.studioId eq filter }
                }
                query.map { it.toEquipmentDto() }
            }

            UserRole.STUDIO_OWNER -> {
                val ownerUuid = ownerId ?: return@transaction emptyList()
                val ownerStudioIds = StudiosTable
                    .slice(StudiosTable.id)
                    .select { StudiosTable.ownerId eq ownerUuid }
                    .map { it[StudiosTable.id] }

                if (ownerStudioIds.isEmpty()) {
                    emptyList()
                } else {
                    val idsToFetch = studioId?.let { filter ->
                        if (ownerStudioIds.contains(filter)) listOf(filter) else emptyList()
                    } ?: ownerStudioIds

                    if (idsToFetch.isEmpty()) {
                        emptyList()
                    } else {
                        EquipmentTable
                            .select { EquipmentTable.studioId inList idsToFetch }
                            .map { it.toEquipmentDto() }
                    }
                }
            }

            UserRole.PHOTOGRAPHER -> emptyList()
        }
    }

    fun updateEquipment(
        userId: UUID,
        role: UserRole,
        equipmentId: UUID,
        request: UpdateEquipmentRequest
    ): EquipmentUpdateResult = transaction {
        val equipmentRow = EquipmentTable.select { EquipmentTable.id eq equipmentId }.singleOrNull()
            ?: return@transaction EquipmentUpdateResult.NotFound

        val studioRow = StudiosTable.select { StudiosTable.id eq equipmentRow[EquipmentTable.studioId] }.single()
        val isOwner = studioRow[StudiosTable.ownerId] == userId
        val isAdmin = role == UserRole.ADMIN

        if (!isOwner && !isAdmin) {
            return@transaction EquipmentUpdateResult.Forbidden
        }

        val normalizedName = request.name?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedBrand = request.brand?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedType = request.type?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedCondition = request.condition?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedSerial = request.serialNumber?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedBarcodeImage = request.barcodeImageUrl?.trim()?.takeIf { it.isNotEmpty() }

        val normalizedRentalPrice = request.rentalPrice?.let { price ->
            if (price <= 0) {
                return@transaction EquipmentUpdateResult.InvalidPayload("rentalPrice must be greater than zero")
            }
            price.toBigDecimalWithScale()
        }

        val normalizedStatus = request.status

        if (
            normalizedName == null &&
            normalizedBrand == null &&
            normalizedType == null &&
            normalizedRentalPrice == null &&
            normalizedCondition == null &&
            normalizedSerial == null &&
            normalizedStatus == null &&
            normalizedBarcodeImage == null
        ) {
            return@transaction EquipmentUpdateResult.InvalidPayload("At least one updatable field must be provided")
        }

        EquipmentTable.update({ EquipmentTable.id eq equipmentId }) { statement ->
            normalizedName?.let { statement[EquipmentTable.name] = it }
            normalizedBrand?.let { statement[EquipmentTable.brand] = it }
            normalizedType?.let { statement[EquipmentTable.type] = it }
            normalizedRentalPrice?.let { statement[EquipmentTable.rentalPrice] = it }
            normalizedCondition?.let { statement[EquipmentTable.condition] = it }
            normalizedSerial?.let { statement[EquipmentTable.serialNumber] = it }
            normalizedStatus?.let { statement[EquipmentTable.status] = it }
            normalizedBarcodeImage?.let { statement[EquipmentTable.barcodeImageUrl] = it }
        }

        val updatedRow = EquipmentTable.select { EquipmentTable.id eq equipmentId }.single()
        EquipmentUpdateResult.Success(updatedRow.toEquipmentDto())
    }

    fun scanEquipment(userId: UUID, role: UserRole, request: ScanRequest): EquipmentScanResult = transaction {
        val barcode = request.barcodeCode.trim()
        val equipmentRow = EquipmentTable
            .select { EquipmentTable.barcodeCode eq barcode }
            .singleOrNull()
            ?: return@transaction EquipmentScanResult.NotFound

        val studioRow = StudiosTable.select { StudiosTable.id eq equipmentRow[EquipmentTable.studioId] }.single()
        val isOwner = studioRow[StudiosTable.ownerId] == userId
        val isAdmin = role == UserRole.ADMIN

        if (!isOwner && !isAdmin) {
            return@transaction EquipmentScanResult.Forbidden
        }

        val newStatus = when (request.action) {
            EquipmentAction.SCAN_OUT -> {
                if (equipmentRow[EquipmentTable.status] != EquipmentStatus.AVAILABLE) {
                    return@transaction EquipmentScanResult.InvalidStatus
                }
                EquipmentStatus.RENTED
            }

            EquipmentAction.SCAN_IN -> {
                if (equipmentRow[EquipmentTable.status] != EquipmentStatus.RENTED) {
                    return@transaction EquipmentScanResult.InvalidStatus
                }
                EquipmentStatus.AVAILABLE
            }
        }

        EquipmentTable.update({ EquipmentTable.id eq equipmentRow[EquipmentTable.id] }) {
            it[status] = newStatus
        }

        val logId = UUID.randomUUID()
        val timestamp = LocalDateTime.now(ZoneOffset.UTC)
        val note = request.notes?.trim()?.takeIf { it.isNotEmpty() }
        EquipmentLogsTable.insert { statement ->
            statement[EquipmentLogsTable.id] = logId
            statement[EquipmentLogsTable.equipmentId] = equipmentRow[EquipmentTable.id]
            statement[EquipmentLogsTable.userId] = userId
            statement[EquipmentLogsTable.action] = request.action
            statement[EquipmentLogsTable.timestamp] = timestamp
            statement[EquipmentLogsTable.notes] = note
        }

        val updatedRow = EquipmentTable.select { EquipmentTable.id eq equipmentRow[EquipmentTable.id] }.single()
        val logDto = EquipmentLogDto(
            id = logId.toString(),
            equipmentId = updatedRow[EquipmentTable.id].toString(),
            userId = userId.toString(),
            action = request.action,
            timestamp = timestamp.toString(),
            notes = note
        )

        EquipmentScanResult.Success(updatedRow.toEquipmentDto(), logDto)
    }
}

sealed interface EquipmentCreateResult {
    data object StudioNotFound : EquipmentCreateResult
    data object Forbidden : EquipmentCreateResult
    data class Success(val equipment: EquipmentDto) : EquipmentCreateResult
}

sealed interface EquipmentUpdateResult {
    data object NotFound : EquipmentUpdateResult
    data object Forbidden : EquipmentUpdateResult
    data class InvalidPayload(val message: String) : EquipmentUpdateResult
    data class Success(val equipment: EquipmentDto) : EquipmentUpdateResult
}

sealed interface EquipmentScanResult {
    data object NotFound : EquipmentScanResult
    data object Forbidden : EquipmentScanResult
    data object InvalidStatus : EquipmentScanResult
    data class Success(val equipment: EquipmentDto, val log: EquipmentLogDto) : EquipmentScanResult
}

private fun Int.toBigDecimalWithScale() = java.math.BigDecimal(this).setScale(2)
