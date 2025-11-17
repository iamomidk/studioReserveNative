package com.kaj.studioreserve.api

import kotlinx.serialization.Serializable

@Serializable
data class EquipmentDto(
    val id: String,
    val studioId: String,
    val name: String,
    val brand: String,
    val type: String,
    val rentalPrice: Double,
    val condition: String,
    val serialNumber: String,
    val status: EquipmentStatus,
    val barcodeCode: String,
    val barcodeImageUrl: String?,
)

@Serializable
data class EquipmentLogDto(
    val id: String,
    val equipmentId: String,
    val userId: String,
    val action: EquipmentAction,
    val timestamp: String,
    val notes: String?,
)

@Serializable
enum class EquipmentAction { CHECK_OUT, CHECK_IN }

@Serializable
enum class EquipmentStatus { AVAILABLE, RENTED, MAINTENANCE }
