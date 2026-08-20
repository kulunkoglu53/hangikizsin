package com.mehmet.barkodokuyucu

data class BarcodeEntry(
    val value: String,
    var format: String,
    var count: Int,
    val firstSeen: Long,
    var lastSeen: Long
)
