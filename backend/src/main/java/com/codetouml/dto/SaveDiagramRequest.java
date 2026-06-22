package com.codetouml.dto;

/**
 * Request to save a diagram to the signed-in user's history.
 *
 * @param title      a name for the saved diagram
 * @param sourceType "code" (pasted Java) or "repo" (a GitHub URL)
 * @param source     the pasted Java code, or the repo URL
 */
public record SaveDiagramRequest(String title, String sourceType, String source) {
}
