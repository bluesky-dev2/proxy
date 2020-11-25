// ========================================================================
// Copyright (c) 1997 MortBay Consulting, Sydney
// $Id: LogSink.java,v 1.1 2004/06/04 21:37:20 gregwilkins Exp $
// ========================================================================

package net.lightbody.bmp.proxy.jetty.log;

import net.lightbody.bmp.proxy.jetty.util.LifeCycle;

import java.io.Serializable;

/* ------------------------------------------------------------ */
/** A Log sink.
 * This class represents both a concrete or abstract sink of
 * Log data.  The default implementation logs to a PrintWriter, but
 * derived implementations may log to files, syslog, or other
 * logging APIs.
 *
 * 
 * @version $Id: LogSink.java,v 1.1 2004/06/04 21:37:20 gregwilkins Exp $
 * @author Greg Wilkins (gregw)
 */
public interface LogSink extends LifeCycle, Serializable
{
    /* ------------------------------------------------------------ */
    public void setLogImpl(LogImpl impl);
   
    /* ------------------------------------------------------------ */
    /** Log a message.
     * This method formats the log information as a string and calls
     * log(String).  It should only be specialized by a derived
     * implementation if the format of the logged messages is to be changed.
     *
     * @param tag Tag for type of log
     * @param msg The message
     * @param frame The frame that generated the message.
     * @param time The time stamp of the message.
     */
    public void log(String tag,
                    Object msg,
                    Frame frame,
                    long time);
    
    /* ------------------------------------------------------------ */
    /** Log a message.
     * The formatted log string is written to the log sink. The default
     * implementation writes the message to a PrintWriter.
     * @param formattedLog 
     */
    public void log(String formattedLog);

};








