package com.keychainvault.app

import java.util.UUID

/**
 * Esp Vault BLE protocol v1 — UUIDs and message parsing.
 * Must match the ESP32-C3 firmware (see BLE_PROTOCOL.md).
 */
object Protocol {
    const val DEVICE_NAME = "EspVault"

    val SVC: UUID = UUID.fromString("81f3d5eb-25b8-4077-aff6-c578a9613ab6")
    val AUTH: UUID = UUID.fromString("7c896164-1090-43d0-ab1d-1a7251069755")
    val STATUS: UUID = UUID.fromString("b33250c0-7157-4f43-8b80-ad6138026c5a")
    val DEVICE_INFO: UUID = UUID.fromString("06d9cadd-eb38-4ce7-b8c7-1c111ca67b3b")
    val VAULT_COUNT: UUID = UUID.fromString("2f096600-5c39-477d-90c2-7f462c8124db")
    val VAULT_LABELS: UUID = UUID.fromString("86192014-db09-4c0d-9541-2e408811b091")
    val TOTP_REQ: UUID = UUID.fromString("fca93d15-df17-4b9d-9c87-8d31a7e69ff8")
    val TOTP_CODE: UUID = UUID.fromString("563456d9-f8f9-4c9d-9cdc-9db51cec980a")
    val PW_COUNT: UUID = UUID.fromString("68f67e11-cdac-4d61-b5bd-58ae373e1f2c")
    val PW_LABELS: UUID = UUID.fromString("7e1d7521-587e-42e1-ae79-6f4a12d1465a")
    val PW_LABEL_PAGE: UUID = UUID.fromString("9c4e2a1f-3b7d-4e8a-9f2c-1d5e6a7b8c9d")
    const val PW_LABELS_PER_PAGE = 20
    val PW_REQ: UUID = UUID.fromString("db65d936-d0ab-4663-a944-1aed62839e0c")
    val PW_ENTRY: UUID = UUID.fromString("fa782901-221c-4cec-9839-52d60fbcb1ff")
    val TIME_SYNC: UUID = UUID.fromString("eca41348-e62b-400a-9098-db4de06d644e")
    val ADD_TOTP: UUID = UUID.fromString("81841413-8a90-40a6-8521-c8c444fce206")
    val LOCK: UUID = UUID.fromString("47ce190e-1fbf-4c54-8c18-a775455ae0de")
    // v1.4.4+ CRUD characteristics (all require unlock + auth)
    val TOTP_DELETE: UUID = UUID.fromString("d29e964e-1377-4b9e-843a-cf3453ed4a54")
    val TOTP_EDIT: UUID = UUID.fromString("22622a17-8714-4361-a7e1-35aae56f337a")
    val PW_ADD: UUID = UUID.fromString("3eb833b0-9943-4c35-9eda-44d456be3745")
    val PW_EDIT: UUID = UUID.fromString("ac74481a-fe9c-40f5-b800-174f5b23f4e5")
    val PW_DELETE: UUID = UUID.fromString("bc172588-0a48-46fb-8105-be7490cb2526")
    // v1.4.5+ first-boot setup (NO auth, only while setup=1; ERR SETUP after)
    val SETUP_SET_PASSWORD: UUID = UUID.fromString("4c73d3a3-e7b3-4db1-9cc1-6b7c1371c595")
    val SETUP_SET_WIFI: UUID = UUID.fromString("293daea1-8da9-425e-baa8-c62557fffad7")
    val SETUP_COMPLETE: UUID = UUID.fromString("f241bf45-78af-40af-9741-062ba41ee9b0")
    // v1.4.5+ BLE auto-start flag (requires unlock + auth)
    val BLE_AUTO: UUID = UUID.fromString("e2e0fbd6-009f-4e45-97d6-c045bf512e66")
    // v1.4.6+ device settings (all require unlock + auth)
    val PW_CHANGE: UUID = UUID.fromString("4a0cb1b6-5c35-4ad6-a253-81edbf811000")
    val WIFI_SET: UUID = UUID.fromString("a9cdd97a-f770-4d5a-9d82-1a224859ce91")
    val TZ: UUID = UUID.fromString("4f0c630a-50a2-4348-ad1b-425a39900942")
    // v1.4.7+ vault export (requires unlock + auth)
    val VAULT_EXPORT: UUID = UUID.fromString("8113d85b-9feb-470e-a36e-5ccbab8dac90")

    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    val ALL_CHARS = listOf(
        AUTH, STATUS, DEVICE_INFO, VAULT_COUNT, VAULT_LABELS,
        TOTP_REQ, TOTP_CODE, PW_COUNT, PW_LABELS, PW_LABEL_PAGE, PW_REQ, PW_ENTRY,
        TIME_SYNC, ADD_TOTP, LOCK,
        TOTP_DELETE, TOTP_EDIT, PW_ADD, PW_EDIT, PW_DELETE,
        SETUP_SET_PASSWORD, SETUP_SET_WIFI, SETUP_COMPLETE, BLE_AUTO,
        PW_CHANGE, WIFI_SET, TZ, VAULT_EXPORT
    )

    /** True if the firmware version supports BLE CRUD (1.4.4+). */
    fun supportsCrud(fw: String): Boolean {
        val parts = fw.split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size < 3) return false
        return parts[0] > 1 || (parts[0] == 1 && (parts[1] > 4 || (parts[1] == 4 && parts[2] >= 4)))
    }

    /** True if the firmware version supports first-boot setup + ble_auto (1.4.5+). */
    fun supportsSetup(fw: String): Boolean {
        val parts = fw.split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size < 3) return false
        return parts[0] > 1 || (parts[0] == 1 && (parts[1] > 4 || (parts[1] == 4 && parts[2] >= 5)))
    }

    /** True if the firmware version supports device settings over BLE (1.4.6+). */
    fun supportsSettings(fw: String): Boolean {
        val parts = fw.split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size < 3) return false
        return parts[0] > 1 || (parts[0] == 1 && (parts[1] > 4 || (parts[1] == 4 && parts[2] >= 6)))
    }

    /** True if the firmware version supports vault export over BLE (1.4.7+). */
    fun supportsExport(fw: String): Boolean {
        val parts = fw.split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size < 3) return false
        return parts[0] > 1 || (parts[0] == 1 && (parts[1] > 4 || (parts[1] == 4 && parts[2] >= 7)))
    }

    /** True if the firmware version supports paged password labels (1.5.0+). */
    fun supportsPagedPwLabels(fw: String): Boolean {
        val parts = fw.split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size < 3) return false
        return parts[0] > 1 || (parts[0] == 1 && parts[1] >= 5)
    }

    /**
     * "KV1;<fw>;<locked 0/1>[;<setup 0/1>][;debug...]" -> Triple(fw, locked, setup), or
     * null if malformed. The 3-part form (firmware < 1.4.5) implies setup=false.
     * Extra parts (debug info) are ignored for the triple but available in raw.
     */
    fun parseDeviceInfo(s: String): Triple<String, Boolean, Boolean>? {
        val p = s.split(";")
        if (p.size < 3 || p[0] != "KV1") return null
        return Triple(p[1], p[2] == "1", p.size >= 4 && p[3] == "1")
    }

    /** "CODE;<label>;<6 digits>;<seconds-left>" -> Triple(label, code, secs). */
    fun parseTotpCode(s: String): Triple<String, String, Int>? {
        val p = s.split(";")
        if (p.size != 4 || p[0] != "CODE") return null
        val secs = p[3].toIntOrNull() ?: return null
        return Triple(p[1], p[2], secs)
    }

    /**
     * "ENTRY;<label>;<username>;<password>" -> Triple(label, username, password).
     * The password itself may contain ';', so split into at most 4 parts.
     */
    fun parsePwEntry(s: String): Triple<String, String, String>? {
        val p = s.split(";", limit = 4)
        if (p.size != 4 || p[0] != "ENTRY") return null
        return Triple(p[1], p[2], p[3])
    }

    /** Same normalization the firmware applies to labels. */
    fun normalizeLabel(s: String): String =
        s.trim().uppercase().replace(" ", "_")

    /** Same base32 check as the firmware's isValidBase32Secret(). */
    fun isValidBase32Secret(s: String): Boolean {
        val t = s.trim().replace(" ", "")
        if (t.length < 8 || t.length > 128) return false
        return t.all { it in 'A'..'Z' || it in 'a'..'z' || it in '2'..'7' || it == '=' }
    }
}
