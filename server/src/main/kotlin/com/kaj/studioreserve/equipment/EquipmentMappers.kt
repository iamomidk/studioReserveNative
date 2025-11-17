package com.kaj.studioreserve.equipment

import org.jetbrains.exposed.sql.ResultRow

fun ResultRow.toEquipmentDto(): EquipmentDto = EquipmentDto(
    id = this[EquipmentTable.id].toString(),
    studioId = this[EquipmentTable.studioId].toString(),
    name = this[EquipmentTable.name],
    brand = this[EquipmentTable.brand],
    type = this[EquipmentTable.type],
    rentalPrice = this[EquipmentTable.rentalPrice].toInt(),
    condition = this[EquipmentTable.condition],
    serialNumber = this[EquipmentTable.serialNumber],
    status = this[EquipmentTable.status],
    barcodeCode = this[EquipmentTable.barcodeCode],
    barcodeImageUrl = this[EquipmentTable.barcodeImageUrl]
)

fun ResultRow.toEquipmentLogDto(): EquipmentLogDto = EquipmentLogDto(
    id = this[EquipmentLogsTable.id].toString(),
    equipmentId = this[EquipmentLogsTable.equipmentId].toString(),
    userId = this[EquipmentLogsTable.userId].toString(),
    action = this[EquipmentLogsTable.action],
    timestamp = this[EquipmentLogsTable.timestamp].toString(),
    notes = this[EquipmentLogsTable.notes]
)
