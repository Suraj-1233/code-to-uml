package com.codetouml.service;

/**
 * Thrown when the layout engine fails to render a diagram (e.g. Smetana crashing on a very large
 * or complex graph), so the client gets a clear message instead of a stack-trace image.
 */
public class DiagramRenderException extends RuntimeException {
    public DiagramRenderException(String message) {
        super(message);
    }
}
