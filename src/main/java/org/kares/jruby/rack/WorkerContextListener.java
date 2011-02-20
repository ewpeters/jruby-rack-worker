/*
 * Copyright (c) 2010 Karol Bucek
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kares.jruby.rack;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadFactory;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import org.jruby.Ruby;
import org.jruby.javasupport.JavaEmbedUtils;

import org.jruby.rack.RackApplication;
import org.jruby.rack.RackApplicationFactory;
import org.jruby.rack.RackInitializationException;
import org.jruby.rack.RackServletContextListener;

/**
 * A context listener which spawns worker threads.
 *
 * @author kares <self_AT_kares_DOT_org>
 */
public class WorkerContextListener implements ServletContextListener {

    /**
     * The worker script to execute (should be a loop of some kind).
     * For scripts included in a separate file use {@link #SCRIPT_PATH_KEY}.
     *
     * <context-param>
     *   <param-name>jruby.worker.script</param-name>
     *   <param-value>require 'delayed/worker'; Delayed::RubyWorker.new.start</param-value>
     * </context-param>
     */
    public static final String SCRIPT_KEY = "jruby.worker.script";

    /**
     * Path to the worker script to be executed - the script will be parsed
     * and executed as a string thus don't rely on features such as __FILE__ !
     *
     * <context-param>
     *   <param-name>jruby.worker.script.path</param-name>
     *   <param-value>lib/delayed/jruby_worker.rb</param-value>
     * </context-param>
     */
    public static final String SCRIPT_PATH_KEY = "jruby.worker.script.path";

    /**
     * The thread count - how many worker (daemon) threads to create.
     */
    public static final String THREAD_COUNT_KEY = "jruby.worker.thread.count";

    /**
     * The thread priority - supported values: NORM, MIN, MAX and integers
     * between 1 - 10.
     */
    public static final String THREAD_PRIORITY_KEY = "jruby.worker.thread.priority";

    // 4 TEST ACCESS
    final Map<RubyWorker, Thread> workers = new HashMap<RubyWorker, Thread>(4);

    /**
     * @param event
     */
    public void contextInitialized(final ServletContextEvent event) {
        final ServletContext context = event.getServletContext();
        // JRuby-Rack :
        final RackApplicationFactory appFactory = (RackApplicationFactory)
                context.getAttribute( RackServletContextListener.FACTORY_KEY );
        if ( appFactory == null ) {
            final String message = 
                    RackApplicationFactory.class.getName() + " not yet initialized - " +
                    "seems this listener is executing before the " +
                    RackServletContextListener.class.getName() + "/RailsSevletContextListener !";
            context.log("[" + WorkerContextListener.class.getName() + "] ERROR: " + message);
            throw new IllegalStateException(message);
        }

        final String workerScript = getWorkerScript(context);
        if ( workerScript == null ) {
            final String message = "no worker script to execute - configure one using '" + SCRIPT_KEY + "' " +
                    "or '" + SCRIPT_PATH_KEY + "' context-param or see previous errors if already configured";
            context.log("[" + WorkerContextListener.class.getName() + "] WARN: " + message);
            return; //throw new IllegalStateException(message);
        }

        final int workersCount = getThreadCount(context);
        
        final ThreadFactory threadFactory = newThreadFactory(context);
        for ( int i = 0; i < workersCount; i++ ) {
            final RackApplication app;
            try {
                app = appFactory.getApplication();
                final RubyWorker worker = newRubyWorker(app.getRuntime(), workerScript);
                final Thread workerThread = threadFactory.newThread(worker);
                workers.put(worker, workerThread);
                workerThread.start();
            }
            catch (RackInitializationException e) {
                context.log("[" + WorkerContextListener.class.getName() + "] ERROR: get rack application failed", e);
            }
        }
        context.log("[" + WorkerContextListener.class.getName() + "] INFO : started " + workers.size() + " worker(s)");
    }

    /**
     * @param event
     */
    public void contextDestroyed(final ServletContextEvent event) {
        final ServletContext context = event.getServletContext();
        //contextDestroyed = true;
        for ( final RubyWorker worker : workers.keySet() ) {
            final Thread workerThread = workers.get(worker);
            try {
                // JRuby seems to ignore Java's Interrupted arithmentic
                // @see http://jira.codehaus.org/browse/JRUBY-4135
                workerThread.interrupt();
                workerThread.join();
                worker.stop();
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            catch (Exception e) {
                context.log("[" + WorkerContextListener.class.getName() + "] WARN: ignoring exception", e);
            }
        }
        context.log("[" + WorkerContextListener.class.getName() + "] INFO: stopped " + workers.size() + " worker(s)");
        workers.clear();
    }

    protected RubyWorker newRubyWorker(final Ruby runtime, final String script) {
        return new RubyWorker(runtime, script);
    }

    protected ThreadFactory newThreadFactory(final ServletContext context) {
        return new WorkerThreadFactory( context.getServletContextName(), getThreadPriority(context) );
    }

    protected int getThreadCount(final ServletContext context) {
        String count = context.getInitParameter(THREAD_COUNT_KEY);
        try {
            if ( count != null ) return Integer.parseInt(count);
        }
        catch (NumberFormatException e) {
            context.log("[" + WorkerContextListener.class.getName() + "] WARN: " +
                        "could not parse " + THREAD_COUNT_KEY + " parameter value = " + count, e);
        }
        return 1;
    }

    protected int getThreadPriority(final ServletContext context) {
        String priority = context.getInitParameter(THREAD_PRIORITY_KEY);
        try {
            if ( priority != null ) {
                if ( "NORM".equalsIgnoreCase(priority) ) return Thread.NORM_PRIORITY;
                else if ( "MIN".equalsIgnoreCase(priority) ) return Thread.MIN_PRIORITY;
                else if ( "MAX".equalsIgnoreCase(priority) ) return Thread.MAX_PRIORITY;
                return Integer.parseInt(priority);
            }
        }
        catch (NumberFormatException e) {
            context.log("[" + WorkerContextListener.class.getName() + "] WARN: " +
                        "could not parse " + THREAD_PRIORITY_KEY + " parameter value = " + priority, e);
        }
        return Thread.NORM_PRIORITY;
    }

    protected String getWorkerScript(final ServletContext context) {
        String script = context.getInitParameter(SCRIPT_KEY);
        if ( script != null ) return script;

        script = context.getInitParameter(SCRIPT_PATH_KEY);
        if ( script != null ) {
            // INSPIRED BY DefaultRackApplicationFactory :
            final InputStream scriptStream = context.getResourceAsStream(script);
            if ( scriptStream != null ) {
                final StringBuilder str = new StringBuilder(256);
                try {
                    int c = scriptStream.read();
                    Reader reader; String coding = "UTF-8";
                    if (c == '#') { // look for a coding: pragma
                        str.append((char) c);
                        while ((c = scriptStream.read()) != -1 && c != 10) {
                            str.append((char) c);
                        }
                        Pattern matchCoding = Pattern.compile("coding:\\s*(\\S+)");
                        Matcher matcher = matchCoding.matcher( str.toString() );
                        if (matcher.find()) coding = matcher.group(1);
                    }

                    str.append((char) c);
                    reader = new InputStreamReader(scriptStream, coding);

                    while ((c = reader.read()) != -1) {
                        str.append((char) c);
                    }
                }
                catch (Exception e) {
                    context.log("[" + WorkerContextListener.class.getName() + "] ERROR: " +
                                "error reading script: '" + script + "'", e);
                    return null;
                }
                script = str.toString();
            }
        }

        return script;
    }

    protected static class RubyWorker implements Runnable {

        protected final Ruby runtime;
        protected final String script;

        public RubyWorker(final Ruby runtime, final String script) {
            this.runtime = runtime;
            this.script = script;
        }

        public void run() {
            runtime.evalScriptlet(script);
        }

        public void stop() {
            // jruby-rack manages the runtimes thus let it terminate !
            //JavaEmbedUtils.terminate(runtime);
        }

    }

}
