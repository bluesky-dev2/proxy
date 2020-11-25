/*
 * Created on Jun 4, 2004
 *
 * To change the template for this generated file go to
 * Window - Preferences - Java - Code Generation - Code and Comments
 */
package net.lightbody.bmp.proxy.jetty.log;

import org.apache.commons.logging.Log;


/* ------------------------------------------------------------ */
/** Log Factory.
 * This is a static facade over the commons logging LogFactory class and it will normally simply 
 * delegate to the a discovered instance of LogFactory.  However, if the system property 
 * "org.mortbay.log.LogFactory.noDiscovery" is set to true, the a static instance of the Jetty
 * Factory is created and this is directly delegated to, thus avoiding the commons discovery
 * mechanism (and problems associated with it).
 * 
 * @author gregw
 */
public class LogFactory {
    static boolean noDiscovery = Boolean.getBoolean("net.lightbody.bmp.proxy.jetty.log.LogFactory.noDiscovery");
    static org.apache.commons.logging.LogFactory factory=noDiscovery?new Factory():org.apache.commons.logging.LogFactory.getFactory();
    
    public static Log getLog(Class logClass)
    {
        return factory.getInstance(logClass);
    }
    
    public static Log getLog(String log)
    {
        return factory.getInstance(log);
    }
    
    public static org.apache.commons.logging.LogFactory getFactory()
    {
        return factory;
    }
    
    public static void release(ClassLoader loader)
    {
        org.apache.commons.logging.LogFactory.release(loader);
    }
    
}
