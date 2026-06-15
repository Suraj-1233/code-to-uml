package com.codetouml.dto;

/** Incoming request carrying the raw Java source to diagram. */
public record GenerateRequest(String code) {
}
