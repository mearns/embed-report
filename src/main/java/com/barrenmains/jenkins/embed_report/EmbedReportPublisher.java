package com.barrenmains.jenkins.embed_report;

import hudson.Launcher;
import hudson.Extension;
import hudson.FilePath;
import hudson.util.FormValidation;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.AbstractProject;
import hudson.model.AbstractDescribableImpl;
import hudson.model.DirectoryBrowserSupport;
import hudson.model.Action;
import hudson.model.Descriptor;
import hudson.model.ProminentProjectAction;
import hudson.model.Result;
import hudson.tasks.Publisher;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.util.ListBoxModel;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerResponse;

import javax.servlet.ServletException;
import java.io.IOException;
import java.io.File;
import java.io.PrintWriter;
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

    private List<Target> targets;


    /**
     * This is the class the implements each of the reports to be generated.
     * Each of the <code>f:repeatable</code> entries from config.jelly will be
     * used to instantiate one of these, and then a list will be past to the
     * {@link EmbedReportPublisher} constructor.
     */
    public static class Target extends AbstractDescribableImpl<Target>
    {
        public static enum Association {
            PROJECT_ONLY("project only"),
            BUILD_ONLY("build only"),
            BOTH("both project and build");

            protected String fLabel;

            Association(String label) {
                this.fLabel = label;
            }

            public String toString() {
                return this.fLabel;
            }
        }

        public String name;
        public String file;
        public int height;
        public Association association;

        // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
        @DataBoundConstructor
        public Target(String name, String file, int height, String association) {
            this.name = name;
            this.file = file;
            this.height = height;
            this.association = Association.valueOf(association);
        }

        public String getName() {
            return this.name;
        }

        public String getFile() {
            return this.file;
        }

        public int getHeight() {
            return this.height;
        }

        public String getAssociation() {
            return this.association.name();
        }

        @Extension
        public static class DescriptorImpl extends Descriptor<Target> {
            @Override
            public String getDisplayName() {
                return "";
            }

            public ListBoxModel doFillAssociationItems()
            {
                ListBoxModel items = new ListBoxModel();
                for (Association a : Association.values())
                {
                    items.add(a.toString(), a.name());
                }
                return items;
            }
        }

        /**
         * Helper function to get the {@link FilePaths} corresponding to destination
         * of this target. Returns an array of two FilePath objects, the first is
         * the target directory, the second is the target file itself (i.e., the archived
         * report).
         */
        protected FilePath[] getTargetPaths(AbstractProject project) {
            FilePath projRoot = new FilePath(project.getRootDir());
            FilePath mine = projRoot.child("embed_report");

            FilePath targetDir = mine.child(this.name);
            FilePath targetFile = targetDir.child((new File(this.file)).getName());

            FilePath[] paths = new FilePath[2];
            paths[0] = targetDir;
            paths[1] = targetFile;

            return paths;
        }

        public FilePath getTargetFile(AbstractProject project)
        {
            return this.getTargetPaths(project)[1];
        }

        public FilePath getSourceFile(AbstractProject project)
        {
            return this.getSourceFile(project.getSomeWorkspace());
        }

        public FilePath getSourceFile(AbstractBuild build)
        {
            return this.getSourceFile(build.getWorkspace());
        }

        protected FilePath getSourceFile(FilePath workspace)
        {
            if (workspace == null) {
                return null;
            }
            //TODO: Use build.getEnvironment(listener).expand(...) to expand env vars in the path.
            //TODO: May need to do it directly on this.file, early on.
            //
            //TODO: Enforce it is within the workspace.
            return workspace.child(this.file);
        }

        public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener)
            throws InterruptedException, IOException
        {
            FilePath sourceFile = this.getSourceFile(build);
            
            FilePath[] paths = this.getTargetPaths(build.getProject());
            FilePath targetDir = paths[0];
            FilePath targetFile = paths[1];

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

            return true;
        }

        public Collection<? extends Action> getProjectActions(AbstractProject project)
        {
            ArrayList<Action> actions = new ArrayList<Action>();
            actions.add(new HtmlAction(project));
            return actions;
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

                FilePath[] paths = EmbedReportPublisher.Target.this.getTargetPaths(project);
                this.fTargetDir = paths[0];
                this.fTargetFile = paths[1];
            }

            public AbstractProject getProject() {
                return this.fProject ;
            }

            public String getIconFileName() {
                //TODO: Configurable Icon?
                return "graph.gif" ;
            }

            public String getDisplayName() {
                return "View " + EmbedReportPublisher.Target.this.getName();
            }

            public String getUrlName() {
                //TODO: This is not the right way to create nested URL namespaces. Not sure if I can.
                //return "embed_report/" + EmbedReportPublisher.Target.this.getName();
                return "embed-" + EmbedReportPublisher.Target.this.getName();
            }

            public String getTitle() {
                return EmbedReportPublisher.Target.this.getName();
            }

            public String getUrl() {
                return this.getUrlName();
            }

            public String getInlineStyle() {
                return "width: 95%; border: 1px solid #666; height: " + EmbedReportPublisher.Target.this.getHeight() + "px;";
            }
            
            public boolean reportReady() throws IOException, InterruptedException {
                return (this.fTargetFile != null && this.fTargetFile.exists());
            }

            public String getHtml() throws IOException, InterruptedException
            {
                if(this.reportReady()) {
                    return "<iframe style='" + this.getInlineStyle() + "' src='" + this.getUrl() + "'></iframe>";
                }
                return "<p class='no-report'>Report not generated.</p>";
            }

            /**
             * Serves the URL subspace specifed by <getUrlName>.
             */
            public void doDynamic(StaplerRequest req, StaplerResponse rsp) throws IOException, InterruptedException, ServletException {
                if(this.reportReady()) {
                    DirectoryBrowserSupport dbs = new DirectoryBrowserSupport(
                        this, this.fTargetFile, EmbedReportPublisher.Target.this.getName(), "graph.gif", true
                    );
                    dbs.generateResponse(req, rsp, this);
                } else {
                    PrintWriter writer = rsp.getWriter();
                    writer.println("<html><head><title>Report Not Generated</title></head><body><h1>Report Not Generated</h1>");
                    writer.println("<p>The report has not been generated, either because the job has never been run, or because it failed to generate the report.</p>");
                    writer.flush();
                }
            }
        }

    }

    @DataBoundConstructor
    public EmbedReportPublisher(List<Target> targets) {
        if (targets == null) {
            this.targets = new ArrayList<Target>(0);
        }
        else {
            this.targets = new ArrayList<Target>(targets);
                                       }
    }

    public List<Target> getTargets() {
        return this.targets;
    }

    public BuildStepMonitor getRequiredMonitorService() {
        //We don't require any synchronization with other builds or steps.
        return BuildStepMonitor.NONE;
    }

    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener)
        throws InterruptedException, IOException
    {
        //This is where we run the step.

        listener.getLogger().println(TAG + "Running...");

        for(Target target: this.targets)
        {
            listener.getLogger().println(TAG + "Processing target '" + target.getName() + "'...");
            target.perform(build, launcher, listener);
        }
        
        listener.getLogger().println(TAG + "Complete.");

        return true;
    }

    /**
     * Returns a set of actions to be added to the Project's main page.
     * These actions provide behaviors and/or UI components to the Job's top-level
     * page. See, for instance, <HtmlAction>.
     */
    @Override
    public Collection<? extends Action> getProjectActions(AbstractProject project)
    {
        ArrayList<Action> actions = new ArrayList<Action>();
        for(Target target: this.targets) {
            actions.addAll(target.getProjectActions(project));
        }
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

}

