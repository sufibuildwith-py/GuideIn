package io.guidein.platform.api;

public final class GuideInException extends RuntimeException {
    private final ErrorCode code;

    public GuideInException(ErrorCode code) {
        this(code, code.title());
    }

    public GuideInException(ErrorCode code, String safeDetail) {
        super(safeDetail);
        this.code = code;
    }

    public ErrorCode code() { return code; }
}

