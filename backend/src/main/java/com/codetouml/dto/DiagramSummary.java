package com.codetouml.dto;

/** A row in the user's diagram history (no source body — that's fetched on open). */
public record DiagramSummary(long id, String title, String sourceType, String createdAt) {
}
