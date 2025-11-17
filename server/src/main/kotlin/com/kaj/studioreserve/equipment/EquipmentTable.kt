package com.kaj.studioreserve.equipment

import org.jetbrains.exposed.sql.Table

object EquipmentTable : Table("equipment") {
    val id = uuid("id")
    val studioId = uuid("studio_id")
    val name = varchar("name", 150)
    val brand = varchar("brand", 100)
    val type = varchar("type", 100)
    val rentalPrice = decimal("rental_price", 12, 2)
    val condition = varchar("condition", 100)
    val serialNumber = varchar("serial_number", 100)
    val barcodeCode = varchar("barcode_code", 100).uniqueIndex("uk_equipment_barcode")
    val barcodeImageUrl = varchar("barcode_image_url", 255).nullable()
    val status = enumerationByName("status", 50, EquipmentStatus::class)

    override val primaryKey = PrimaryKey(id)
}
