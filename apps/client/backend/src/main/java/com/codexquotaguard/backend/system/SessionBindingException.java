package com.codexquotaguard.backend.system;

/** Fixed local identity/contract failure, without filesystem or remote details. */
public final class SessionBindingException extends RuntimeException {
    public SessionBindingException() { super("本设备绑定信息不可用，请检查统计程序设备登记或联系管理员。"); }
}
