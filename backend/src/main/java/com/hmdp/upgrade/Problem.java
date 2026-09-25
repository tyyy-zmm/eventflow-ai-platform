package com.hmdp.upgrade;

public class Problem extends RuntimeException {
    public final int status;
    public final String code;
    public Problem(int status, String code) { super(code); this.status=status; this.code=code; }
}
