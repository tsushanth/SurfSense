package com.kreativekoala.surfsense.insights

data class ExportRow(val date: String, val device: String, val category: String, val seconds: Int)

object CsvExport {
    /** Header date,device,category,seconds; RFC 4180 quoting; rows sorted by date, device, category; ends with "\n". */
    fun build(rows: List<ExportRow>): String {
        val sb = StringBuilder("date,device,category,seconds\n")
        val sorted = rows.sortedWith(compareBy({ it.date }, { it.device }, { it.category }))
        for (r in sorted) {
            sb.append(field(r.date)).append(',')
                .append(field(r.device)).append(',')
                .append(field(r.category)).append(',')
                .append(r.seconds).append('\n')
        }
        return sb.toString()
    }

    private fun field(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
}
