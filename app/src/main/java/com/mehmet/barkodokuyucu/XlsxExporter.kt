package com.mehmet.barkodokuyucu

import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object XlsxExporter {
    private val df = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale("tr", "TR"))

    fun writeWorkbook(out: OutputStream, entries: List<BarcodeEntry>) {
        ZipOutputStream(out).use { z ->
            add(z, "[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""")
            add(z, "_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            add(z, "xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Barkodlar" sheetId="1" r:id="rId1"/></sheets></workbook>""")
            add(z, "xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""")
            add(z, "xl/worksheets/sheet1.xml", sheet(entries))
        }
    }

    private fun add(z: ZipOutputStream, path: String, text: String) {
        z.putNextEntry(ZipEntry(path)); z.write(text.toByteArray()); z.closeEntry()
    }

    private fun sheet(entries: List<BarcodeEntry>): String {
        val b = StringBuilder("""<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" state="frozen"/></sheetView></sheetViews><cols><col min="1" max="1" width="8" customWidth="1"/><col min="2" max="2" width="24" customWidth="1"/><col min="3" max="3" width="16" customWidth="1"/><col min="4" max="4" width="10" customWidth="1"/><col min="5" max="6" width="22" customWidth="1"/></cols><sheetData>""")
        b.append(row(1, listOf("Sıra","Barkod","Format","Adet","İlk Okuma","Son Okuma"), setOf(0,1,2,3,4,5)))
        entries.forEachIndexed { i,e ->
            b.append(row(i+2, listOf((i+1).toString(), e.value, e.format, e.count.toString(), df.format(Date(e.firstSeen)), df.format(Date(e.lastSeen))), setOf(1,2,4,5)))
        }
        b.append("</sheetData><autoFilter ref=\"A1:F${entries.size+1}\"/></worksheet>")
        return b.toString()
    }

    private fun row(n:Int, values:List<String>, textCols:Set<Int>):String {
        val b=StringBuilder("<row r=\"$n\">")
        values.forEachIndexed { i,v ->
            val ref="${('A'.code+i).toChar()}$n"
            if(i in textCols) b.append("<c r=\"$ref\" t=\"inlineStr\"><is><t>${esc(v)}</t></is></c>")
            else b.append("<c r=\"$ref\"><v>${esc(v)}</v></c>")
        }
        return b.append("</row>").toString()
    }

    private fun esc(s:String)=s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;")
}
