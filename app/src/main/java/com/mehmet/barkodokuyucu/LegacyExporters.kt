package com.mehmet.barkodokuyucu

import java.io.OutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LegacyExporters {
    private val df = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale("tr", "TR"))

    fun writeTxt(out: OutputStream, entries: List<BarcodeEntry>) {
        OutputStreamWriter(out, Charsets.UTF_8).use { w ->
            w.write("Sıra\tBarkod\tFormat\tAdet\tİlk Okuma\tSon Okuma\n")
            entries.forEachIndexed { i, e ->
                w.write("${i + 1}\t${safe(e.value)}\t${safe(e.format)}\t${e.count}\t${df.format(Date(e.firstSeen))}\t${df.format(Date(e.lastSeen))}\n")
            }
        }
    }

    fun writeXls(out: OutputStream, entries: List<BarcodeEntry>) {
        OutputStreamWriter(out, Charsets.UTF_8).use { w ->
            w.write("<html><head><meta charset=\"UTF-8\"></head><body><table border=\"1\">")
            w.write("<tr><th>Sıra</th><th>Barkod</th><th>Format</th><th>Adet</th><th>İlk Okuma</th><th>Son Okuma</th></tr>")
            entries.forEachIndexed { i, e ->
                w.write("<tr>")
                w.write("<td>${i + 1}</td>")
                w.write("<td style=\"mso-number-format:'\\@';\">${html(e.value)}</td>")
                w.write("<td>${html(e.format)}</td>")
                w.write("<td>${e.count}</td>")
                w.write("<td>${df.format(Date(e.firstSeen))}</td>")
                w.write("<td>${df.format(Date(e.lastSeen))}</td>")
                w.write("</tr>")
            }
            w.write("</table></body></html>")
        }
    }

    private fun safe(s: String) = s.replace("\t", " ").replace("\r", " ").replace("\n", " ")
    private fun html(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
