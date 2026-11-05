package com.xavier.backend.reporting.service;

/** An exported report: its file name and its RFC 4180 CSV content. */
public record ReportFile(String filename, String csv) {
}
