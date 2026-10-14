package com.expensetracker.shared.error;

/** A bounded application error; adapters translate codes without exposing internal data. */
public final class DomainException extends RuntimeException {
    public enum Kind { NOT_FOUND, FORBIDDEN, INVALID, CONFLICT, UNAUTHENTICATED, RATE_LIMITED }

    private final Kind kind;
    private final String code;
    private int retryAfterSeconds;

    private DomainException(Kind kind, String code) {
        super(code);
        this.kind = kind;
        this.code = code;
    }

    public static DomainException notFound() { return new DomainException(Kind.NOT_FOUND, "not_found"); }
    public static DomainException forbidden() { return new DomainException(Kind.FORBIDDEN, "forbidden"); }
    public static DomainException invalid(String code) { return new DomainException(Kind.INVALID, code); }
    public static DomainException conflict(String code) { return new DomainException(Kind.CONFLICT, code); }
    public static DomainException unauthenticated(String code) {
        return new DomainException(Kind.UNAUTHENTICATED, code);
    }
    public static DomainException tooManyRequests(int retryAfterSeconds) {
        var error = new DomainException(Kind.RATE_LIMITED, "too_many_attempts");
        error.retryAfterSeconds = Math.max(1, retryAfterSeconds);
        return error;
    }

    public Kind kind() { return kind; }
    public String code() { return code; }
    public int retryAfterSeconds() { return retryAfterSeconds; }
}
