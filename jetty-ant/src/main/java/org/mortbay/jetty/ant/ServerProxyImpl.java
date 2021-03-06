// ========================================================================
// Copyright 2006-2007 Sabre Holdings.
// ------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// ========================================================================

package org.mortbay.jetty.ant;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.RequestLogHandler;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.mortbay.jetty.ant.utils.ServerProxy;
import org.mortbay.jetty.ant.utils.TaskLog;
import org.mortbay.jetty.ant.utils.WebApplicationProxy;
import org.xml.sax.SAXException;

/**
 * A proxy class for interaction with Jetty server object. Used to have some
 * level of abstraction over standard Jetty classes.
 *
 * @author Jakub Pawlowicz
 */
public class ServerProxyImpl implements ServerProxy
{

    /** Proxied Jetty server object. */
    private Server server;

    /** Collection of context handlers (web application contexts). */
    private ContextHandlerCollection contexts;

    /** Location of jetty.xml file. */
    private File jettyXml;

    /** List of connectors. */
    private List connectors;

    /** Request logger. */
    private RequestLog requestLog;

    /** User realms. */
    private List loginServices;

    /** List of added web applications. */
    private Map webApplications = new HashMap();

    /**
     * Default constructor. Creates a new Jetty server with a standard connector
     * listening on a given port.
     *
     * @param connectors
     * @param loginServicesList
     * @param requestLog
     * @param jettyXml
     */
    public ServerProxyImpl(List connectors, List loginServicesList, RequestLog requestLog,
            File jettyXml)
    {
        server = new Server();
        server.setStopAtShutdown(true);

        this.connectors = connectors;
        this.loginServices = loginServicesList;
        this.requestLog = requestLog;
        this.jettyXml = jettyXml;
        configure();
    }

    /**
     * @see org.mortbay.jetty.ant.utils.ServerProxy#addWebApplication(WebApplicationProxy,
     *      int)
     */
    public void addWebApplication(WebApplicationProxy webApp, int scanIntervalSeconds)
    {
        webApp.createApplicationContext(contexts);

        if (scanIntervalSeconds > 0)
        {
            webApplications.put(webApp, new Integer(scanIntervalSeconds));
        }
    }

    /**
     * Configures Jetty server before adding any web applications to it.
     */
    private void configure()
    {
        // Applies external configuration via jetty.xml
        applyJettyXml();

        // Configures connectores for this server instance.
        Iterator connectorIterator = connectors.iterator();
        while (connectorIterator.hasNext())
        {
            Connector jettyConnector = (Connector) connectorIterator.next();
            server.addConnector(jettyConnector);
        }

        /* server.addLoginService() not implemented
        // Configures login services
        Iterator servicesIterator = loginServices.iterator();
        while (servicesIterator.hasNext())
        {
            LoginService service = (LoginService) servicesIterator.next();
            server.addLoginService(service);
        }
        */

        // Does not cache resources, to prevent Windows from locking files
        Resource.setDefaultUseCaches(false);

        // Set default server handlers
        configureHandlers();
    }

    private void configureHandlers()
    {
        RequestLogHandler requestLogHandler = new RequestLogHandler();
        if (requestLog != null)
        {
            requestLogHandler.setRequestLog(requestLog);
        }

        contexts = (ContextHandlerCollection) server
                .getChildHandlerByClass(ContextHandlerCollection.class);
        if (contexts == null)
        {
            contexts = new ContextHandlerCollection();
            HandlerCollection handlers = (HandlerCollection) server
                    .getChildHandlerByClass(HandlerCollection.class);
            if (handlers == null)
            {
                handlers = new HandlerCollection();
                server.setHandler(handlers);
                handlers.setHandlers(new Handler[] { contexts, new DefaultHandler(),
                        requestLogHandler });
            }
            else
            {
                handlers.addHandler(contexts);
            }
        }
    }

    /**
     * Applies jetty.xml configuration to the Jetty server instance.
     */
    private void applyJettyXml()
    {
        if (jettyXml != null && jettyXml.exists())
        {
            TaskLog.log("Configuring jetty from xml configuration file = "
                    + jettyXml.getAbsolutePath());
            XmlConfiguration configuration;
            try
            {
                configuration = new XmlConfiguration(Resource.toURL(jettyXml));
                configuration.configure(server);
            }
            catch (MalformedURLException e)
            {
                throw new RuntimeException(e);
            }
            catch (SAXException e)
            {
                throw new RuntimeException(e);
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * @see org.mortbay.jetty.ant.utils.ServerProxy#start()
     */
    public void start()
    {
        try
        {
            server.start();
            startScanners();
            server.join();

        }
        catch (InterruptedException e)
        {
            new RuntimeException(e);
        }
        catch (Exception e)
        {
            new RuntimeException(e);
        }
    }

    /**
     * Starts web applications' scanners.
     */
    private void startScanners() throws Exception
    {
        Iterator i = webApplications.keySet().iterator();
        while (i.hasNext())
        {
            WebApplicationProxyImpl webApp = (WebApplicationProxyImpl) i.next();
            Integer scanIntervalSeconds = (Integer) webApplications.get(webApp);
            JettyRunTask.startScanner(webApp, scanIntervalSeconds.intValue());
        }
    }

    /**
     * @see org.mortbay.jetty.ant.utils.ServerProxy#getProxiedObject()
     */
    public Object getProxiedObject()
    {
        return server;
    }
}
