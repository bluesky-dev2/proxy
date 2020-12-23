// ========================================================================
// Copyright (c) 1997 MortBay Consulting, Sydney
// $Id: NullLogSink.java,v 1.1 2004/06/04 21:37:20 gregwilkins Exp $
// ========================================================================

package net.lightbody.bmp.proxy.jetty.log;

public class NullLogSink implements LogSink {
    private boolean started;

    public String getOptions() {
        return null;
    }

    public void setOptions(String options) {
    }

    public void log(String tag, Object msg, Frame frame, long time) {
    }

    public void log(String formattedLog) {
    }

    public void start() {
        started = true;
    }

    public void stop() {
        started = false;
    }

    public boolean isStarted() {
        return started;
    }

    public void setLogImpl(LogImpl impl) {
    }
}
