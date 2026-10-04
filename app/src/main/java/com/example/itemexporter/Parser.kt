package com.example.itemexporter

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A piece of text read from the accessibility tree together with its on-screen bounds. */
data class RawNode(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val cy: Int get() = (top + bottom) / 2
    val cx: Int get() = (left + right) / 2
    val h: Int get() = max(bottom - top, 1)
}

data class Item(
    val name: String,
    val barcode: String,
    val stock: String,
    val waste: String,
    val price: Long?,
    val note: String
) {
    /** Higher = more complete. Used to replace half-visible cards seen earlier while scrolling. */
    val score: Int
        get() = name.length + note.length +
            (if (price != null) 1000 else 0) +
            (if (stock.isNotEmpty()) 50 else 0) +
            (if (waste.isNotEmpty()) 50 else 0)
}

object Parser {
    private val barcodeRe = Regex("^\\d{8,14}$")
    private val priceRe = Regex("^\\d{1,3}(,\\d{3})+$")
    private val numericOnlyRe = Regex("^[\\d.,%\\s:/-]+$")
    private val numberRe = Regex("^\\d+(\\.\\d+)?%?$")
    private val firstNumberRe = Regex("\\d+(?:\\.\\d+)?")
    private val totalLabelRe = Regex("^تعداد\\s*:?$")
    private val totalMergedRe = Regex("^تعداد\\s*:\\s*(\\d+)$")

    /** Unifies Persian/Arabic digits, separators and Arabic letter variants. */
    fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                in '\u06F0'..'\u06F9' -> sb.append('0' + (c - '\u06F0'))
                in '\u0660'..'\u0669' -> sb.append('0' + (c - '\u0660'))
                '\u066C', '\u060C' -> sb.append(',')
                '\u066B' -> sb.append('.')
                '\u064A' -> sb.append('\u06CC') // ي -> ی
                '\u0643' -> sb.append('\u06A9') // ك -> ک
                '\u200F', '\u200E', '\u202A', '\u202B', '\u202C' -> {}
                else -> sb.append(c)
            }
        }
        return sb.toString().trim()
    }

    private fun priceOf(text: String): Long? {
        val hasRial = text.contains("ریال")
        val t = text.replace("ریال", "").trim()
        if (priceRe.matches(t)) return t.replace(",", "").toLongOrNull()
        if (hasRial && Regex("^\\d+$").matches(t)) return t.toLongOrNull()
        return null
    }

    private fun prepare(raw: List<RawNode>, minTop: Int): List<RawNode> =
        raw.map { it.copy(text = normalize(it.text)) }
            .filter { it.text.isNotEmpty() && it.cy >= minTop }
            .sortedWith(compareBy({ it.cy }, { -it.left }))

    /** Total number of items shown in the page header ("تعداد: 1192"), if visible. */
    fun parseTotal(raw: List<RawNode>): Int? {
        val nodes = raw.map { it.copy(text = normalize(it.text)) }.filter { it.text.isNotEmpty() }
        nodes.forEach { n ->
            totalMergedRe.find(n.text)?.let { return it.groupValues[1].toIntOrNull() }
        }
        val label = nodes.firstOrNull { totalLabelRe.matches(it.text) } ?: return null
        return numberLeftOf(label, nodes)?.toDoubleOrNull()?.toInt()
    }

    /** Nearest purely numeric node on the same row, placed to the left of [label] (RTL layout). */
    private fun numberLeftOf(label: RawNode, nodes: List<RawNode>): String? {
        val tol = max(label.h, 24)
        return nodes
            .filter { it !== label && abs(it.cy - label.cy) <= tol && it.cx < label.cx }
            .filter { numberRe.matches(it.text) && it.text.length < 8 }
            .maxByOrNull { it.cx }
            ?.text?.removeSuffix("%")
    }

    private fun valueFor(label: RawNode, nodes: List<RawNode>): String {
        val afterColon = if (label.text.contains(':')) label.text.substringAfter(':') else ""
        firstNumberRe.find(afterColon)?.let { return it.value }
        return numberLeftOf(label, nodes) ?: ""
    }

    fun parse(raw: List<RawNode>, minTop: Int = 0): List<Item> {
        val nodes = prepare(raw, minTop)
        val barcodes = nodes.filter { barcodeRe.matches(it.text.replace(" ", "")) }.sortedBy { it.cy }
        if (barcodes.isEmpty()) return emptyList()
        val prices = nodes.filter { priceOf(it.text) != null }
        val priceRows = prices.map { it.cy } + nodes.filter { it.text.endsWith("ریال") }.map { it.cy }

        // typical distance between two consecutive cards
        val gaps = barcodes.zipWithNext { a, b -> b.cy - a.cy }.sorted()
        val pitch = if (gaps.isNotEmpty()) gaps[gaps.size / 2] else 0

        val result = ArrayList<Item>()
        for ((i, b) in barcodes.withIndex()) {
            val prev = barcodes.getOrNull(i - 1)
            val next = barcodes.getOrNull(i + 1)

            val priceNode = prices
                .filter { it.cy > b.cy && (next == null || it.cy < next.cy) }
                .minByOrNull { it.cy }

            // everything above this line belongs to the previous card (its note row included)
            val prevEnd = if (prev != null) {
                prices.filter { it.cy > prev.cy && it.cy < b.cy }.minByOrNull { it.cy }?.bottom ?: prev.cy
            } else Int.MIN_VALUE

            val span = if (pitch > 0) (pitch * 0.75).toInt() else b.h * 6
            val lower = max(prevEnd, b.cy - span)

            // ---- name (lines above the barcode) ----
            val nameNodes = nodes.filter { n ->
                n.cy > lower && n.cy < b.cy - b.h / 2 &&
                    !isNoise(n.text) &&
                    priceRows.none { abs(it - n.cy) <= max(n.h, 24) / 2 + 4 }
            }
            val lines = ArrayList<MutableList<RawNode>>()
            for (n in nameNodes) {
                val last = lines.lastOrNull()
                if (last != null && abs(last.first().cy - n.cy) <= max(n.h, 20) / 2) last.add(n)
                else lines.add(mutableListOf(n))
            }
            val name = lines.joinToString(" ") { line ->
                line.sortedByDescending { it.right }.joinToString(" ") { it.text }
            }.replace(Regex("\\s+"), " ").trim()

            // ---- stock / waste labels ----
            // labels must START with the label text: notes such as "بررسی موجودی سیستم" must not match
            val near = nodes.filter { n ->
                n.cy > lower && n.cy <= b.cy + b.h &&
                    priceRows.none { abs(it - n.cy) <= max(n.h, 24) / 2 + 4 }
            }
            val stock = near.firstOrNull { it.text.startsWith("موجودی") }?.let { valueFor(it, nodes) } ?: ""
            val waste = near.firstOrNull { it.text.startsWith("درصد ضایعات") }?.let { valueFor(it, nodes) } ?: ""

            // ---- note (same row as the price) ----
            val note = priceNode?.let { p ->
                nodes.filter { n ->
                    n !== p && abs(n.cy - p.cy) <= max(p.h, 24) &&
                        !n.text.endsWith("ریال") && priceOf(n.text) == null && n.text.length > 1
                }.sortedByDescending { it.right }.joinToString(" ") { it.text }
            } ?: ""

            result.add(
                Item(
                    name = name,
                    barcode = b.text.replace(" ", ""),
                    stock = stock,
                    waste = waste,
                    price = priceNode?.let { priceOf(it.text) },
                    note = note.trim()
                )
            )
        }
        return result
    }

    private fun isNoise(t: String): Boolean =
        t.startsWith("موجودی") || t.startsWith("درصد ضایعات") || t == "انتخاب" || t == "ریال" ||
            numericOnlyRe.matches(t) || barcodeRe.matches(t) || priceOf(t) != null
}
