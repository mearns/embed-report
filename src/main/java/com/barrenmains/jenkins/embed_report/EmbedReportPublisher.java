package com.barrenmains.jenkins.embed_report;

import hudson.Launcher;
import hudson.Extension;
import hudson.FilePath;
import hudson.util.FormValidation;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.AbstractProject;
import hudson.model.DirectoryBrowserSupport;
import hudson.model.Action;
import hudson.model.ProminentProjectAction;
import hudson.model.Result;
import hudson.tasks.Publisher;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerResponse;

import javax.servlet.ServletException;
import java.io.IOException;
import java.io.File;
import java.util.*;

/**
 * Publisher step that saves an artifact and publishes it embedded directly
 * in the Job's page.
 *
 * <p>
 * When the user configures the project and enables this builder,
 * {@link DescriptorImpl#newInstance(StaplerRequest)} is invoked
 * and a new {@link EmbedReportPublisher} is created. The created
 * instance is persisted to the project configuration XML by using
 * XStream, so this allows you to use instance fields (like {@link #name})
 * to remember the configuration.
 *
 * <p>
 * When a build is performed, the {@link #perform(AbstractBuild, Launcher, BuildListener)}
 * method will be invoked. 
 *
 * @author Brian Mearns
 */
public class EmbedReportPublisher extends Publisher
{

    public static final String TAG = "[embed_report]";

    private final String name;
    private final String file;

    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public EmbedReportPublisher(String name, String file) {
        this.name = name;
        this.file = file;
    }

    public String getName() {
        return this.name;
    }

    public String getFile() {
        return this.file;
    }

    public BuildStepMonitor getRequiredMonitorService() {
        //We don't require any synchronization with other builds or steps.
        return BuildStepMonitor.NONE;
    }

    protected FilePath[] getTargetPaths(AbstractProject project) {
        FilePath projRoot = new FilePath(project.getRootDir());

        FilePath targetDir = projRoot.child("embed_report").child(this.name);
        FilePath targetFile = targetDir.child((new File(this.file)).getName());

        FilePath[] paths = new FilePath[2];
        paths[0] = targetDir;
        paths[1] = targetFile;

        return paths;
    }

    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener)
        throws InterruptedException, IOException
    {
        //This is where we run the step.

        listener.getLogger().println(TAG + "Running...");
        
        //TODO: Use build.getEnvironment(listener).expand(...) to expand env vars in the path.
        //TODO: May need to do it directly on this.file, early on.
        FilePath sourceFile = build.getWorkspace().child(this.file);
        
        FilePath[] paths = this.getTargetPaths(build.getProject());
        FilePath targetDir = paths[0];
        FilePath targetFile = paths[1];

        listener.getLogger().println(TAG + "Archiving " + sourceFile + " to " + targetFile + ".");
        if(!sourceFile.exists()) {
            listener.error("Specified report file '" + this.file + "' does not exist.");
            build.setResult(Result.FAILURE);
            return true;
        }

        //Make sure output directory exists.
        //TODO: Maybe delete other files in here?
        if(!targetDir.exists()) {
            targetDir.mkdirs();
        } 

        //Copy to dest
        sourceFile.copyTo(targetFile);
        
        listener.getLogger().println(TAG + "Archive complete.");

        //XXX:
        return true;
    }

    /**
     * Returns a set of actions to be added to the Project's main page.
     * These actions provide behaviors and/or UI components to the Job's top-level
     * page. See, for instance, <HtmlAction>.
     */
    @Override
    public Collection<? extends Action> getProjectActions(AbstractProject project) {
        ArrayList<Action> actions = new ArrayList<Action>();
        actions.add(new HtmlAction(project));
        return actions;
    }


    // Overridden for better type safety.
    // If your plugin doesn't really define any property on Descriptor,
    // you don't have to do this.
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    /**
     * Descriptor for {@link EmbedReportPublisher}. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     *
     * <p>
     * See <tt>src/main/resources/hudson/plugins/hello_world/EmbedReportPublisher/*.jelly</tt>
     * for the actual HTML fragment for the configuration screen.
     */
    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {
        /**
         * In order to load the persisted global configuration, you have to 
         * call load() in the constructor.
         */
        public DescriptorImpl() {
            load();
        }

        //TODO: Validation

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types 
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "Embed Reports";
        }
    }


    /**
     * An action for the publisher. Instances of Action returns by <getProjectActions>
     * can show up in the Project/Job top-page. The <getIconFileName>, <getDisplayName>,
     * and <getUrlName> are used to add an item for the action to the left-hand
     * menu in the Job's page. 
     *
     * By extending <ProminentProjectAction>, we are added to the table/list of
     * prominent links at the top of the Job's page as well.
     *
     * If <getIconFileName> returns null, we aren't added to either location.
     *
     */
    public class HtmlAction implements ProminentProjectAction
    {

        protected AbstractProject fProject;
        protected FilePath fTargetDir;
        protected FilePath fTargetFile;

        public HtmlAction(AbstractProject project) {
            this.fProject = project;
            FilePath[] paths = EmbedReportPublisher.this.getTargetPaths(this.fProject);
            this.fTargetDir = paths[0];
            this.fTargetFile = paths[1];
        }

        public AbstractProject getProject() {
            return this.fProject ;
        }

        public String getIconFileName() {
            return "graph.gif" ;
        }

        public String getDisplayName() {
            return "View " + EmbedReportPublisher.this.getName();
        }

        public String getUrlName() {
            //TODO: This is not the right way to create nested URL namespaces. Not sure if I can.
            //return "embed_report/" + EmbedReportPublisher.this.getName();
            return "embed-" + EmbedReportPublisher.this.getName();
        }

        public String getTitle() {
            return EmbedReportPublisher.this.getName();
        }

        public String getUrl() {
            return this.getUrlName();
        }

        /**
         * Serves the URL subspace specifed by <getUrlName>.
         */
        public void doDynamic(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
            DirectoryBrowserSupport dbs = new DirectoryBrowserSupport(
                this, this.fTargetFile, EmbedReportPublisher.this.getName(), "graph.gif", true
            );
            dbs.generateResponse(req, rsp, this);
        }
    }

}

