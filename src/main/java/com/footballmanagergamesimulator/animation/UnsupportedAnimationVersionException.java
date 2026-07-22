package com.footballmanagergamesimulator.animation;

/** Raised when a persisted recipe requests generator code not shipped by this build. */
public class UnsupportedAnimationVersionException extends IllegalStateException {

    public UnsupportedAnimationVersionException(int version) {
        super("Animation generator version " + version
                + " is not available; restore that frozen renderer or serve archived frames");
    }
}
