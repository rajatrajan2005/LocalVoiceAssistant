package com.localvoice.assistant.voice;

import kotlin.jvm.functions.Function1;

public final class TtsCallback implements Function1<float[], Integer> {
    public interface Sink {
        int accept(float[] samples);
    }

    private final Sink sink;

    public TtsCallback(Sink sink) {
        this.sink = sink;
    }

    @Override
    public Integer invoke(float[] samples) {
        return sink.accept(samples);
    }
}
