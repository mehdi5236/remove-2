package com.example.itemexporter

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Minimal dependency-free .xlsx writer (RTL sheet, frozen header, filter). */
object XlsxWriter {
    private val headers = listOf(
        "ردیف", "نام کالا", "بارکد", "موجودی", "درصد ضایعات ماهیانه", "قیمت (ریال)", "توضیحات"
    )
    private val cols = listOf("A", "B", "C", "D", "E", "F", "G")
    private val widths = listOf(8, 48, 18, 10, 20, 16, 36)

    private const val NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    fun build(items: List<Item>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", contentTypes())
            put("_rels/.rels", rootRels())
            put("xl/workbook.xml", workbook())
            put("xl/_rels/workbook.xml.rels", workbookRels())
            put("xl/styles.xml", styles())
            put("xl/worksheets/sheet1.xml", sheet(items))
        }
        return bos.toByteArray()
    }

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when {
                c == '&' -> sb.append("&amp;")
                c == '<' -> sb.append("&lt;")
                c == '>' -> sb.append("&gt;")
                c == '"' -> sb.append("&quot;")
                c.code < 0x20 && c != '\n' && c != '\t' -> {}
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun str(ref: String, s: String, style: Int = 0) =
        "<c r=\"$ref\" t=\"inlineStr\" s=\"$style\"><is><t xml:space=\"preserve\">${esc(s)}</t></is></c>"

    private fun num(ref: String, v: String, style: Int = 0) = "<c r=\"$ref\" s=\"$style\"><v>$v</v></c>"

    private fun sheet(items: List<Item>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sb.append("<worksheet xmlns=\"$NS\" xmlns:r=\"$NS_R\">")
        sb.append("<sheetViews><sheetView rightToLeft=\"1\" workbookViewId=\"0\">")
        sb.append("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>")
        sb.append("<selection pane=\"bottomLeft\"/></sheetView></sheetViews>")
        sb.append("<sheetFormatPr defaultRowHeight=\"15\"/>")
        sb.append("<cols>")
        widths.forEachIndexed { i, w ->
            sb.append("<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"$w\" customWidth=\"1\"/>")
        }
        sb.append("</cols><sheetData>")

        sb.append("<row r=\"1\" ht=\"24\" customHeight=\"1\">")
        headers.forEachIndexed { i, h -> sb.append(str("${cols[i]}1", h, 1)) }
        sb.append("</row>")

        items.forEachIndexed { idx, it ->
            val r = idx + 2
            sb.append("<row r=\"$r\">")
            sb.append(num("A$r", (idx + 1).toString()))
            sb.append(str("B$r", it.name))
            sb.append(str("C$r", it.barcode, 3)) // text, keeps leading zeros / full precision
            sb.append(if (it.stock.isNotEmpty()) num("D$r", it.stock) else str("D$r", ""))
            sb.append(if (it.waste.isNotEmpty()) num("E$r", it.waste) else str("E$r", ""))
            sb.append(if (it.price != null) num("F$r", it.price.toString(), 2) else str("F$r", ""))
            sb.append(str("G$r", it.note))
            sb.append("</row>")
        }
        sb.append("</sheetData>")
        sb.append("<autoFilter ref=\"A1:G${maxOf(items.size + 1, 2)}\"/>")
        sb.append("</worksheet>")
        return sb.toString()
    }

    private fun contentTypes() =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
            "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
            "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
            "</Types>"

    private fun rootRels() =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
            "</Relationships>"

    private fun workbook() =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<workbook xmlns=\"$NS\" xmlns:r=\"$NS_R\">" +
            "<sheets><sheet name=\"اقلام\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"

    private fun workbookRels() =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
            "</Relationships>"

    private fun styles() =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<styleSheet xmlns=\"$NS\">" +
            "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font>" +
            "<font><b/><sz val=\"11\"/><color rgb=\"FFFFFFFF\"/><name val=\"Calibri\"/></font></fonts>" +
            "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill>" +
            "<fill><patternFill patternType=\"gray125\"/></fill>" +
            "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFD81B60\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>" +
            "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
            "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
            "<cellXfs count=\"4\">" +
            "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
            "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\" wrapText=\"1\"/></xf>" +
            "<xf numFmtId=\"3\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>" +
            "<xf numFmtId=\"49\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>" +
            "</cellXfs>" +
            "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
            "</styleSheet>"
}
