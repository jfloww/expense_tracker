package com.expensetracker.shared.error;

/** A bounded application error; adapters translate codes without exposing internal data. */
public final class DomainException extends RuntimeException {
    public enum Kind { NOT_FOUND, FORBIDDEN, INVALID, CONFLICT }

    private final Kind kind;
    private final String code;

    private DomainException(Kind kind, String code) {
        super(code);
        this.kind = kind;
        this.code = code;
    }

    public static DomainException notFound() { return new DomainException(Kind.NOT_FOUND, "not_found"); }
    public static DomainException forbidden() { return new DomainException(Kind.FORBIDDEN, "forbidden"); }
    public static DomainException invalid(String code) { return new DomainException(Kind.INVALID, code); }
    public static DomainException conflict(String code) { return new DomainException(Kind.CONFLICT, code); }

    public Kind kind() { return kind; }
    public String code() { return code; }
}
