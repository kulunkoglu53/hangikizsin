package com.mehmet.barkodokuyucu

// v2 build trigger: corrected mode UI, safe navigation inset and sharing flow.
data class BarcodeEntry(
    val value: String,
    var format: String,
    var count: Int,
    val firstSeen: Long,
    var lastSeen: Long
)
