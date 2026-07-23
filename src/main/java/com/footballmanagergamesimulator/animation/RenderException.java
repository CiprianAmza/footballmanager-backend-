package com.footballmanagergamesimulator.animation;

/**
 * Recoverable failure while rendering a specialised pattern (a script that does
 * not fit the frame budget, or a physically unreachable geometry). The director
 * catches this and falls back to a total safe render; it never masks general
 * programming errors, which continue to propagate.
 */
public class RenderException extends RuntimeException {
    public RenderException(String message) {
        super(message);
    }
}
