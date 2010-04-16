package com.izforge.izpack.panels.process;

import com.izforge.izpack.api.adaptator.IXMLElement;
import com.izforge.izpack.api.adaptator.IXMLParser;
import com.izforge.izpack.api.adaptator.impl.XMLParser;
import com.izforge.izpack.api.data.AutomatedInstallData;
import com.izforge.izpack.api.data.Pack;
import com.izforge.izpack.api.data.ResourceManager;
import com.izforge.izpack.api.data.binding.OsModel;
import com.izforge.izpack.api.handler.AbstractUIHandler;
import com.izforge.izpack.api.handler.AbstractUIProcessHandler;
import com.izforge.izpack.api.rules.Condition;
import com.izforge.izpack.api.substitutor.SubstitutionType;
import com.izforge.izpack.api.substitutor.VariableSubstitutor;
import com.izforge.izpack.core.rules.RulesEngineImpl;
import com.izforge.izpack.util.Debug;
import com.izforge.izpack.util.IoHelper;
import com.izforge.izpack.util.OsConstraintHelper;
import com.izforge.izpack.util.substitutor.VariableSubstitutorImpl;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * This class does alle the work for the process panel.
 * <p/>
 * It responsible for
 * <ul>
 * <li>parsing the process spec XML file
 * <li>performing the actions described therein
 * </ul>
 *
 * @author Tino Schwarze
 */
public class ProcessPanelWorker implements Runnable
{

    /**
     * Name of resource for specifying processing parameters.
     */
    private static final String SPEC_RESOURCE_NAME = "ProcessPanel.Spec.xml";

    private VariableSubstitutor vs;

    protected AbstractUIProcessHandler handler;

    private ArrayList<ProcessPanelWorker.ProcessingJob> jobs = new ArrayList<ProcessPanelWorker.ProcessingJob>();

    private boolean result = true;

    private static PrintWriter logfile = null;

    private String logfiledir = null;

    protected AutomatedInstallData idata;

    private Map<Boolean, List<ButtonConfig>> buttonConfigs = new HashMap<Boolean, List<ButtonConfig>>();
    private VariableSubstitutor variableSubstitutor;

    /**
     * The constructor.
     *
     * @param idata               The installation data.
     * @param handler             The handler to notify of progress.
     * @param variableSubstitutor
     */
    public ProcessPanelWorker(AutomatedInstallData idata, AbstractUIProcessHandler handler, VariableSubstitutor variableSubstitutor)
            throws IOException
    {
        this.handler = handler;
        this.idata = idata;
        this.vs = variableSubstitutor;

        // Removed this test in order to move out of the CTOR (ExecuteForPack
        // Patch)
        // if (!readSpec())
        // throw new IOException("Error reading processing specification");
        this.variableSubstitutor = new VariableSubstitutorImpl(this.idata
                .getVariables());
    }

    private boolean readSpec() throws IOException
    {
        InputStream input;
        try
        {
            input = ResourceManager.getInstance().getInputStream(SPEC_RESOURCE_NAME);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return false;
        }

        IXMLParser parser = new XMLParser();
        IXMLElement spec;
        try
        {
            spec = parser.parse(input);
        }
        catch (Exception e)
        {
            System.err.println("Error parsing XML specification for processing.");
            System.err.println(e.toString());
            return false;
        }

        if (!spec.hasChildren())
        {
            return false;
        }

        // Handle logfile
        IXMLElement lfd = spec.getFirstChildNamed("logfiledir");
        if (lfd != null)
        {
            logfiledir = lfd.getContent();
        }

        for (IXMLElement job_el : spec.getChildrenNamed("job"))
        {
            // normally use condition attribute, but also read conditionid to not break older versions.
            String conditionid = job_el.hasAttribute("condition") ? job_el.getAttribute("condition") : job_el.hasAttribute("conditionid") ? job_el.getAttribute("conditionid") : null;
            if ((conditionid != null) && (conditionid.length() > 0))
            {
                Debug.trace("Condition for job.");
                Condition cond = RulesEngineImpl.getCondition(conditionid);
                if ((cond != null) && !cond.isTrue())
                {
                    Debug.trace("condition is not fulfilled.");
                    // skip, if there is a condition and this condition isn't true
                    continue;
                }
            }
            Debug.trace("Condition is fulfilled or not existent.");
            // ExecuteForPack Patch
            // Check if processing required for pack
            List<IXMLElement> forPacks = job_el.getChildrenNamed("executeForPack");
            if (!jobRequiredFor(forPacks))
            {
                continue;
            }

            // first check OS constraints - skip jobs not suited for this OS
            List<OsModel> constraints = OsConstraintHelper.getOsList(job_el);

            if (OsConstraintHelper.oneMatchesCurrentSystem(constraints))
            {
                List<ProcessPanelWorker.Processable> ef_list = new ArrayList<ProcessPanelWorker.Processable>();

                String job_name = job_el.getAttribute("name", "");

                for (IXMLElement ef : job_el.getChildrenNamed("executefile"))
                {
                    String ef_name = ef.getAttribute("name");

                    if ((ef_name == null) || (ef_name.length() == 0))
                    {
                        System.err.println("missing \"name\" attribute for <executefile>");
                        return false;
                    }

                    List<String> args = new ArrayList<String>();

                    for (IXMLElement arg_el : ef.getChildrenNamed("arg"))
                    {
                        String arg_val = arg_el.getContent();

                        args.add(arg_val);
                    }

                    List<String> envvars = new ArrayList<String>();

                    for (IXMLElement env_el : ef.getChildrenNamed("env"))
                    {
                        String env_val = env_el.getContent();

                        envvars.add(env_val);
                    }


                    ef_list.add(new ProcessPanelWorker.ExecutableFile(ef_name, args, envvars));
                }

                for (IXMLElement ef : job_el.getChildrenNamed("executeclass"))
                {
                    String ef_name = ef.getAttribute("name");
                    if ((ef_name == null) || (ef_name.length() == 0))
                    {
                        System.err.println("missing \"name\" attribute for <executeclass>");
                        return false;
                    }

                    List<String> args = new ArrayList<String>();
                    for (IXMLElement arg_el : ef.getChildrenNamed("arg"))
                    {
                        String arg_val = arg_el.getContent();
                        args.add(arg_val);
                    }

                    ef_list.add(new ProcessPanelWorker.ExecutableClass(ef_name, args));
                }

                if (ef_list.isEmpty())
                {
                    Debug.trace("Nothing to do for job '" + job_name + "'");
                }
                else
                {
                    this.jobs.add(new ProcessingJob(job_name, ef_list));
                }
            }
        }

        buttonConfigs.put(Boolean.FALSE, new ArrayList<ButtonConfig>());
        buttonConfigs.put(Boolean.TRUE, new ArrayList<ButtonConfig>());

        for (IXMLElement ef : spec.getChildrenNamed("onFail"))
        {
            String conditionid = ef.hasAttribute("condition") ? ef.getAttribute("condition") : ef.hasAttribute("conditionid") ? ef.getAttribute("conditionid") : null;
            boolean unlockPrev = ef.hasAttribute("previous") ? Boolean.parseBoolean(ef.getAttribute("previous")) : false;
            boolean unlockNext = ef.hasAttribute("next") ? Boolean.parseBoolean(ef.getAttribute("next")) : false;
            buttonConfigs.get(Boolean.FALSE).add(new ButtonConfig(conditionid, unlockPrev, unlockNext));
        }
        for (IXMLElement ef : spec.getChildrenNamed("onSuccess"))
        {
            String conditionid = ef.hasAttribute("condition") ? ef.getAttribute("condition") : ef.hasAttribute("conditionid") ? ef.getAttribute("conditionid") : null;
            boolean unlockPrev = ef.hasAttribute("previous") ? Boolean.parseBoolean(ef.getAttribute("previous")) : false;
            buttonConfigs.get(Boolean.TRUE).add(new ButtonConfig(conditionid, unlockPrev, true));
        }

        return true;
    }

    /**
     * This is called when the processing thread is activated.
     * <p/>
     * Can also be called directly if asynchronous processing is not desired.
     */
    public void run()
    {
        // ExecuteForPack patch
        // Read spec only here... not before, cause packs are otherwise
        // all selected or de-selected
        try
        {
            if (!readSpec())
            {
                System.err.println("Error parsing XML specification for processing.");
                return;
            }
        }
        catch (IOException ioe)
        {
            System.err.println(ioe.toString());
            return;
        }

        // Create logfile if needed. Do it at this point because
        // variable substitution needs selected install path.
        if (logfiledir != null)
        {
            logfiledir = IoHelper.translatePath(logfiledir, variableSubstitutor);

            File lf;

            String appVersion = idata.getVariable("APP_VER");

            if (appVersion != null)
            {
                appVersion = "V" + appVersion;
            }
            else
            {
                appVersion = "undef";
            }

            String identifier = (new SimpleDateFormat("yyyyMMddHHmmss")).format(new Date());

            identifier = appVersion.replace(' ', '_') + "_" + identifier;

            try
            {
                lf = File.createTempFile("Install_" + identifier + "_", ".log",
                        new File(logfiledir));
                logfile = new PrintWriter(new FileOutputStream(lf), true);
            }
            catch (IOException e)
            {
                Debug.error(e);
                // TODO throw or throw not, that's the question...
            }
        }

        this.handler.startProcessing(this.jobs.size());

        for (ProcessPanelWorker.ProcessingJob pj : this.jobs)
        {
            this.handler.startProcess(pj.name);

            this.result = pj.run(this.handler, this.vs);

            this.handler.finishProcess();

            if (!this.result)
            {
                break;
            }
        }

        boolean unlockNext = true;
        boolean unlockPrev = false;

        // get the ButtonConfigs matching the this.result
        for (ButtonConfig bc : buttonConfigs.get(this.result))
        {
            String conditionid = bc.getConditionid();
            if ((conditionid != null) && (conditionid.length() > 0))
            {
                Debug.trace("Condition for job.");
                Condition cond = RulesEngineImpl.getCondition(conditionid);
                if ((cond != null) && !cond.isTrue())
                {
                    Debug.trace("condition is not fulfilled.");
                    // skip, if there is a condition and this condition isn't true
                    continue;
                }
            }

            unlockNext = bc.isUnlockNext();
            unlockPrev = bc.isUnlockPrev();
            break;
        }

        this.handler.finishProcessing(unlockPrev, unlockNext);
        if (logfile != null)
        {
            logfile.close();
        }
    }

    /**
     * Start the compilation in a separate thread.
     */
    public void startThread()
    {
        Thread processingThread = new Thread(this, "processing thread");
        // will call this.run()
        processingThread.start();
    }

    /**
     * Return the result of the process execution.
     *
     * @return true if all processes succeeded, false otherwise.
     */
    public boolean getResult()
    {
        return this.result;
    }

    interface Processable
    {

        /**
         * @param handler The UI handler for user interaction and to send output to.
         * @return true on success, false if processing should stop
         */
        public boolean run(AbstractUIProcessHandler handler, VariableSubstitutor vs);
    }

    private static class ProcessingJob implements ProcessPanelWorker.Processable
    {

        public String name;

        private List<ProcessPanelWorker.Processable> processables;

        public ProcessingJob(String name, List<ProcessPanelWorker.Processable> processables)
        {
            this.name = name;
            this.processables = processables;
        }

        public boolean run(AbstractUIProcessHandler handler, VariableSubstitutor vs)
        {
            for (ProcessPanelWorker.Processable pr : this.processables)
            {
                if (!pr.run(handler, vs))
                {
                    return false;
                }
            }

            return true;
        }

    }

    private static class ExecutableFile implements ProcessPanelWorker.Processable
    {

        private String filename;

        private List<String> arguments;

        private List<String> envvariables;

        protected AbstractUIProcessHandler handler;

        public ExecutableFile(String fn, List<String> args, List<String> envvars)
        {
            this.filename = fn;
            this.arguments = args;
            this.envvariables = envvars;
        }

        public boolean run(AbstractUIProcessHandler handler, VariableSubstitutor vs)
        {
            this.handler = handler;

            List<String> params = new ArrayList<String>(this.arguments.size() + 1);

            params.add(vs.substitute(this.filename, SubstitutionType.TYPE_PLAIN));

            for (String argument : this.arguments)
            {
                params.add(vs.substitute(argument, SubstitutionType.TYPE_PLAIN));
            }

            ProcessBuilder pb = new ProcessBuilder(params);
            Map<String, String> environment = pb.environment();
            for (String envvar : envvariables)
            {
                String ev = vs.substitute(envvar, SubstitutionType.TYPE_PLAIN);
                int i = ev.indexOf("=");
                if (i > 0)
                {
                    environment.put(ev.substring(0, i), ev.substring(i + 1));
                }
            }

            try
            {

                Process p = pb.start();

                ProcessPanelWorker.ExecutableFile.OutputMonitor stdoutMon = new ProcessPanelWorker.ExecutableFile.OutputMonitor(this.handler, p.getInputStream(), false);
                ProcessPanelWorker.ExecutableFile.OutputMonitor stderrMon = new ProcessPanelWorker.ExecutableFile.OutputMonitor(this.handler, p.getErrorStream(), true);
                Thread stdoutThread = new Thread(stdoutMon);
                Thread stderrThread = new Thread(stderrMon);
                stdoutThread.setDaemon(true);
                stderrThread.setDaemon(true);
                stdoutThread.start();
                stderrThread.start();

                try
                {
                    int exitStatus = p.waitFor();

                    stopMonitor(stdoutMon, stdoutThread);
                    stopMonitor(stderrMon, stderrThread);

                    if (exitStatus != 0)
                    {
                        if (this.handler.askQuestion("Process execution failed",
                                "Continue anyway?", AbstractUIHandler.CHOICES_YES_NO,
                                AbstractUIHandler.ANSWER_YES) == AbstractUIHandler.ANSWER_NO)
                        {
                            return false;
                        }
                    }
                }
                catch (InterruptedException ie)
                {
                    p.destroy();
                    this.handler.emitError("process interrupted", ie.toString());
                    return false;
                }
            }
            catch (IOException ioe)
            {
                this.handler.emitError("I/O error", ioe.toString());
                return false;
            }

            return true;
        }

        private void stopMonitor(ProcessPanelWorker.ExecutableFile.OutputMonitor m, Thread t)
        {
            // taken from com.izforge.izpack.util.FileExecutor
            m.doStop();
            long softTimeout = 500;
            try
            {
                t.join(softTimeout);
            }
            catch (InterruptedException e)
            {
            }

            if (!t.isAlive())
            {
                return;
            }

            t.interrupt();
            long hardTimeout = 500;
            try
            {
                t.join(hardTimeout);
            }
            catch (InterruptedException e)
            {
            }
        }

        static public class OutputMonitor implements Runnable
        {

            private boolean stderr = false;

            private AbstractUIProcessHandler handler;

            private BufferedReader reader;

            private Boolean stop = false;

            public OutputMonitor(AbstractUIProcessHandler handler, InputStream is, boolean stderr)
            {
                this.stderr = stderr;
                this.reader = new BufferedReader(new InputStreamReader(is));
                this.handler = handler;
            }

            public void run()
            {
                try
                {
                    String line;
                    while ((line = reader.readLine()) != null)
                    {
                        this.handler.logOutput(line, stderr);

                        // log output also to file given in ProcessPanelSpec

                        if (logfile != null)
                        {
                            logfile.println(line);
                        }

                        synchronized (this.stop)
                        {
                            if (stop)
                            {
                                return;
                            }
                        }
                    }
                }
                catch (IOException ioe)
                {
                    this.handler.logOutput(ioe.toString(), true);

                    // log errors also to file given in ProcessPanelSpec

                    if (logfile != null)
                    {
                        logfile.println(ioe.toString());
                    }

                }

            }

            public void doStop()
            {
                synchronized (this.stop)
                {
                    this.stop = true;
                }
            }

        }

    }

    /**
     * Tries to create a class that has an empty contstructor and a method
     * run(AbstractUIProcessHandler, String[]) If found, it calls the method and processes all
     * returned exceptions
     */
    private static class ExecutableClass implements ProcessPanelWorker.Processable
    {

        final private String myClassName;

        final private List<String> myArguments;

        protected AbstractUIProcessHandler myHandler;

        public ExecutableClass(String className, List<String> args)
        {
            myClassName = className;
            myArguments = args;
        }

        public boolean run(AbstractUIProcessHandler aHandler, VariableSubstitutor varSubstitutor)
        {
            boolean result = false;
            myHandler = aHandler;

            String params[] = new String[myArguments.size()];

            int i = 0;
            for (String myArgument : myArguments)
            {
                params[i++] = varSubstitutor.substitute(myArgument, SubstitutionType.TYPE_PLAIN);
            }

            try
            {
                ClassLoader loader = this.getClass().getClassLoader();
                Class procClass = loader.loadClass(myClassName);

                Object o = procClass.newInstance();
                Method m = procClass.getMethod("run", new Class[]{AbstractUIProcessHandler.class,
                        String[].class});

                if (m.getReturnType().getName().equals("boolean"))
                {
                    result = (Boolean) m.invoke(o, new Object[]{myHandler, params});
                }
                else
                {
                    m.invoke(o, new Object[]{myHandler, params});
                    result = true;
                }
            }
            catch (SecurityException e)
            {
                myHandler.emitError("Post Processing Error",
                        "Security exception thrown when processing class: " + myClassName);
            }
            catch (ClassNotFoundException e)
            {
                myHandler.emitError("Post Processing Error", "Cannot find processing class: "
                        + myClassName);
            }
            catch (NoSuchMethodException e)
            {
                myHandler.emitError("Post Processing Error",
                        "Processing class does not have 'run' method: " + myClassName);
            }
            catch (IllegalAccessException e)
            {
                myHandler.emitError("Post Processing Error", "Error accessing processing class: "
                        + myClassName);
            }
            catch (InvocationTargetException e)
            {
                myHandler.emitError("Post Processing Error", "Invocation Problem calling : "
                        + myClassName + ", " + e.getCause().getMessage());
            }
            catch (Exception e)
            {
                myHandler.emitError("Post Processing Error",
                        "Exception when running processing class: " + myClassName + ", "
                                + e.getMessage());
            }
            catch (Error e)
            {
                myHandler.emitError("Post Processing Error",
                        "Error when running processing class: " + myClassName + ", "
                                + e.getMessage());
            }
            catch (Throwable e)
            {
                myHandler.emitError("Post Processing Error",
                        "Error when running processing class: " + myClassName + ", "
                                + e.getMessage());
            }
            return result;
        }
    }

    /*------------------------ ExecuteForPack PATCH -------------------------*/
    /*
     * Verifies if the job is required for any of the packs listed. The job is required for a pack
     * in the list if that pack is actually selected for installation. <br><br> <b>Note:</b><br>
     * If the list of selected packs is empty then <code>true</code> is always returned. The same
     * is true if the <code>packs</code> list is empty.
     *
     * @param packs a <code>Vector</code> of <code>String</code>s. Each of the strings denotes
     * a pack for which the schortcut should be created if the pack is actually installed.
     *
     * @return <code>true</code> if the shortcut is required for at least on pack in the list,
     * otherwise returns <code>false</code>.
     */
    /*--------------------------------------------------------------------------*/
    /*
     * @design
     *
     * The information about the installed packs comes from GUIInstallData.selectedPacks. This assumes
     * that this panel is presented to the user AFTER the PacksPanel.
     *
     * /*--------------------------------------------------------------------------
     */

    private boolean jobRequiredFor(List<IXMLElement> packs)
    {
        String selected;
        String required;

        if (packs.size() == 0)
        {
            return (true);
        }

        // System.out.println ("Number of selected packs is "
        // +installData.selectedPacks.size () );

        for (int i = 0; i < idata.getSelectedPacks().size(); i++)
        {
            selected = idata.getSelectedPacks().get(i).name;

            // System.out.println ("Selected pack is " + selected);

            for (IXMLElement pack : packs)
            {
                required = pack.getAttribute("name", "");
                // System.out.println ("Attribute name is " + required);
                if (selected.equals(required))
                {
                    // System.out.println ("Return true");
                    return (true);
                }
            }
        }
        return (false);
    }

}

