package com.footballmanagergamesimulator.animation;

public class UnsupportedAnimationVersionException extends IllegalStateException {
    public UnsupportedAnimationVersionException(int version) {
        super("animation generator version " + version + " is not installed; restore it or serve archived frames");
    }
}
