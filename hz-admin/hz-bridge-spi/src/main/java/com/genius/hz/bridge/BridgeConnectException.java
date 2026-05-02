package com.genius.hz.bridge;

public class BridgeConnectException extends Exception {
    public BridgeConnectException(String msg, Throwable cause) { super(msg, cause); }
    public BridgeConnectException(String msg)                 { super(msg); }
}
