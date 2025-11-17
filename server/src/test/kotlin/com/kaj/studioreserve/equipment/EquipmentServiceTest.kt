package com.kaj.studioreserve.equipment

import com.kaj.studioreserve.TestDatabase
import com.kaj.studioreserve.studios.StudiosTable
import com.kaj.studioreserve.users.UserRole
import com.kaj.studioreserve.users.UsersTable
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.transaction

class EquipmentServiceTest {
    private val service = EquipmentService()
    private val ownerId = UUID.randomUUID()
    private val studioId = UUID.randomUUID()

    @BeforeTest
    fun setUp() {
        TestDatabase.setup()
        seedOwner()
    }

    @AfterTest
    fun tearDown() {
        TestDatabase.reset()
    }

    @Test
    fun `scan out transitions available equipment to rented`() {
        val equipment = createEquipment()

        val result = service.scanEquipment(
            userId = ownerId,
            role = UserRole.STUDIO_OWNER,
            request = ScanRequest(barcodeCode = equipment.barcodeCode, action = EquipmentAction.SCAN_OUT, notes = null)
        )

        assertTrue(result is EquipmentScanResult.Success)
        assertEquals(EquipmentStatus.RENTED, result.equipment.status)
    }

    @Test
    fun `scan in transitions rented equipment to available`() {
        val equipment = createEquipment()
        service.scanEquipment(ownerId, UserRole.STUDIO_OWNER, ScanRequest(equipment.barcodeCode, EquipmentAction.SCAN_OUT, null))

        val result = service.scanEquipment(ownerId, UserRole.STUDIO_OWNER, ScanRequest(equipment.barcodeCode, EquipmentAction.SCAN_IN, "returned"))

        assertTrue(result is EquipmentScanResult.Success)
        assertEquals(EquipmentStatus.AVAILABLE, result.equipment.status)
        assertEquals("returned", result.log.notes)
    }

    @Test
    fun `scan out fails when equipment not available`() {
        val equipment = createEquipment(status = EquipmentStatus.RENTED)

        val result = service.scanEquipment(ownerId, UserRole.STUDIO_OWNER, ScanRequest(equipment.barcodeCode, EquipmentAction.SCAN_OUT, null))

        assertTrue(result is EquipmentScanResult.InvalidStatus)
    }

    @Test
    fun `non owner cannot scan equipment`() {
        val equipment = createEquipment()

        val result = service.scanEquipment(UUID.randomUUID(), UserRole.PHOTOGRAPHER, ScanRequest(equipment.barcodeCode, EquipmentAction.SCAN_OUT, null))

        assertTrue(result is EquipmentScanResult.Forbidden)
    }

    private fun seedOwner() {
        transaction {
            UsersTable.insert { statement ->
                statement[id] = ownerId
                statement[name] = "Owner"
                statement[phoneNumber] = "+989100000000"
                statement[email] = "owner@example.com"
                statement[passwordHash] = "hash"
                statement[role] = UserRole.STUDIO_OWNER
                statement[avatarUrl] = null
                statement[createdAt] = java.time.LocalDateTime.now()
            }

            StudiosTable.insert { statement ->
                statement[id] = studioId
                statement[ownerId] = ownerId
                statement[name] = "Studio"
                statement[description] = "desc"
                statement[province] = "province"
                statement[city] = "city"
                statement[address] = "address"
                statement[mapCoordinates] = "0,0"
                statement[photos] = emptyList()
            }
        }
    }

    private fun createEquipment(status: EquipmentStatus = EquipmentStatus.AVAILABLE): EquipmentDto {
        val request = CreateEquipmentRequest(
            name = "Camera",
            brand = "Brand",
            type = "DSLR",
            rentalPrice = 5000,
            condition = "good",
            serialNumber = "SN",
        )

        val result = service.createEquipment(ownerId, studioId, request)
        check(result is EquipmentCreateResult.Success)

        if (status != EquipmentStatus.AVAILABLE) {
            transaction {
                EquipmentTable.update({ EquipmentTable.id eq UUID.fromString(result.equipment.id) }) { stmt ->
                    stmt[EquipmentTable.status] = status
                }
            }
        }

        return result.equipment
    }
}
