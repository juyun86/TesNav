package com.garan.tesnav.service

import java.nio.file.Files
import java.util.zip.GZIPInputStream
import org.junit.Assert.*
import org.junit.Test

class PhoneDiagnosticLogWriterTest {
    @Test fun sessionQueryIsEncodedExactlyOnce() {
        val url = diagnosticUrl("127.0.0.1", "session:a", 12)
        assertEquals(7766, url.port)
        assertEquals("/v3/diagnostics", url.path)
        assertEquals("session:a", java.net.URLDecoder.decode(url.query.substringAfter("sessionId=").substringBefore('&'), "UTF-8"))
    }
    @Test fun concatenatedMembersReadAllRecordsAndFilesStayBounded() {
        val directory = Files.createTempDirectory("phone-diag-").toFile()
        try {
            val userFile = java.io.File(directory, "keep.txt").apply { writeText("user") }
            val writer = PhoneDiagnosticLogWriter(directory, maxBytes = 110, maxFiles = 2)
            for (i in 1..12) writer.append("{\"sequence\":$i}")
            val files = directory.listFiles()!!.filter { it.name.endsWith(".gz") }
            assertTrue(files.size <= 2)
            val lines = files.flatMap { file -> GZIPInputStream(file.inputStream()).bufferedReader().use { it.readLines() } }
            assertTrue(lines.contains("{\"sequence\":12}"))
            assertTrue(lines.all { it.startsWith("{\"sequence\":") && it.endsWith("}") })
            assertEquals("user", userFile.readText())
        } finally { directory.deleteRecursively() }
    }
    @Test fun eachWriteIsACompleteReadableRecordWithoutClosingRecorder() {
        val directory = Files.createTempDirectory("phone-diag-").toFile()
        try {
            val writer = PhoneDiagnosticLogWriter(directory)
            writer.append("{\"kind\":\"first\"}")
            writer.append("{\"kind\":\"second\"}")
            val records = GZIPInputStream(directory.listFiles()!!.single().inputStream()).bufferedReader().use { it.readLines() }
            assertEquals(2, records.size)
        } finally { directory.deleteRecursively() }
    }
}
