package com.codetouml.dto;

/** A saved diagram with its source, returned when the user opens a history item. */
public record DiagramDetail(long id, String title, String sourceType, String source) {
}
