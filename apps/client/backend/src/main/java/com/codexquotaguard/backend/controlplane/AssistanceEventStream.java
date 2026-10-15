package com.codexquotaguard.backend.controlplane;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Only three data-free signals can cross this backend boundary, never arbitrary SSE data. */
public final class AssistanceEventStream implements AutoCloseable {
    public enum Signal {
        CONNECTED("connected"), STATE_CHANGED("state_changed"), HEARTBEAT("heartbeat");
        private final String eventName;
        Signal(String eventName) { this.eventName=eventName; }
        public String eventName() { return eventName; }
    }
    private final InputStream input;
    private final AtomicBoolean closed=new AtomicBoolean();
    private int totalBytes;
    public AssistanceEventStream(InputStream input) { this.input=Objects.requireNonNull(input); }

    public Signal next() throws IOException {
        String event=null;
        boolean data=false;
        int frameBytes=0;
        StringBuilder line=new StringBuilder();
        try {
            while(!closed.get()) {
                int value=input.read();
                if(value<0) {
                    if(frameBytes>0)throw invalid();
                    return null;
                }
                if(++frameBytes>4096 || ++totalBytes>65_536 || value>127)throw invalid();
                if(value=='\r')continue;
                if(value!='\n') {
                    if(line.length()>=512)throw invalid();
                    line.append((char)value);continue;
                }
                String field=line.toString();line.setLength(0);
                if(field.isEmpty()) {
                    if(event==null && !data){frameBytes=0;continue;}
                    if(!data)throw invalid();
                    return switch(event==null?"":event) {
                        case "connected" -> Signal.CONNECTED;
                        case "state_changed" -> Signal.STATE_CHANGED;
                        case "heartbeat" -> Signal.HEARTBEAT;
                        default -> throw invalid();
                    };
                }
                if(field.startsWith(":"))continue;
                if(field.startsWith("event:") && event==null){event=field.substring(6).trim();continue;}
                if(field.startsWith("data:") && !data && "{}".equals(field.substring(5).trim())){data=true;continue;}
                throw invalid();
            }
            return null;
        } catch(IOException ignored) {throw invalid();}
    }
    private static IOException invalid() {return new IOException("Assistance event stream unavailable.");}
    @Override public void close() {
        if(closed.compareAndSet(false,true))try{input.close();}catch(IOException ignored){ }
    }
}
