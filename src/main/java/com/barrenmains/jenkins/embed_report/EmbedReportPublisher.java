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
import org.kohsuke.stapler.AncestorInPath;
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
     * used to instantiate one of these, and then a list will be passed to the
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
        public String additional_files;
        public String[] fListOfFiles;
        public int height;
        public Association association;

        // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
        @DataBoundConstructor
        public Target(String name, String file, String additional_files, int height, String association) {
            this.name = name;
            this.file = file;
            this.height = height;
            this.association = Association.valueOf(association);
            this.additional_files = additional_files;

            String [] raw = additional_files.split(",");
            ArrayList<String> all_files = new ArrayList(raw.length+1);
            all_files.add(this.file);
            for(String rawName : raw) {
                rawName = rawName.trim();
                if(rawName.length() > 0) {
                    all_files.add(rawName);
                }
            }
            this.fListOfFiles = all_files.toArray(new String[all_files.size()]);
        }

        public String getAdditionalFiles() {
            return this.additional_files;
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

        /**
         * Validates the file field during project config.
         *
         * This verifies that they specified a path relative to workspace.
         */
        public FormValidation doCheckFile(@AncestorInPath AbstractProject project, @QueryParameter String value)
            throws IOException, ServletException
        {
            FilePath ws = project.getSomeWorkspace();
            if (ws == null) {
                return FormValidation.ok();
            }
            return ws.validateRelativePath(value, false, true);
        }

        @Extension
        public static class DescriptorImpl extends Descriptor<Target> {
            @Override
            public String getDisplayName() {
                return "";
            }

            /**
             * Populates the option in the 'association' select box field in config.jelly.
             * Uses the {@link Association} enum.
             */
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
         * Returns the path to the target directory, where all report files are stored.
         */
        protected FilePath getTargetDir(File rootDir) {
            FilePath mine = (new FilePath(rootDir)).child("embed_report");
            return mine.child(this.name);
        }

        protected FilePath getProjectTargetDir(AbstractProject project) {
            return this.getTargetDir(project.getRootDir());
        }

        protected FilePath getBuildTargetDir(AbstractBuild build) {
            return this.getTargetDir(build.getRootDir());
        }

        /**
         * Returns the file path to the destination file within the given target directory
         * to which the specified source file should be copied.
         */
        protected FilePath getTargetPath(FilePath targetDir, String name)
        {
            return targetDir.child(name);
        }


        /**
         * Copies the report file for archiving in the project of the given build.
         */
        protected void archiveProjectFiles(AbstractBuild build, BuildListener listener)
            throws InterruptedException, IOException
        {
            this.archiveFiles(build.getProject().getRootDir(), build, listener);
        }

        /**
         * Copies the report file for archiving in the specified build.
         */
        protected void archiveBuildFiles(AbstractBuild build, BuildListener listener)
            throws InterruptedException, IOException
        {
            this.archiveFiles(build.getRootDir(), build, listener);
        }

        /**
         * Copies the report file from the given build into the specified target directory.
         */
        protected void archiveFiles(File rootDir, AbstractBuild build, BuildListener listener)
            throws InterruptedException, IOException
        {
            //All we really need to do is copy the specified file into our target directory.
            
            FilePath targetDir = this.getTargetDir(rootDir);
            
            //Make sure output directory exists.
            //TODO: Maybe delete other files in here?
            if(!targetDir.exists()) {
                targetDir.mkdirs();
            } 

            for(String name : this.fListOfFiles)
            { 
                FilePair pair = this.getSourceAndTarget(targetDir, build, listener, name);
                if (pair != null) {
                    if(!pair.source.exists()) {
                        listener.error("Specified report file '" + name + "' does not exist.");
                        build.setResult(Result.FAILURE);
                    }
                    else {
                    
                        //TODO: This isn't working right, and validateRelativePath may not even do what I think it does.
                        //if(build.getWorkspace().validateRelativePath(this.file, false, true).kind != FormValidation.Kind.OK)
                        //{
                        //    listener.error("Source file is not relative to workspace.");
                        //    build.setResult(Result.FAILURE);
                        //    return true;
                        //}

                        //Copy to dest
                        pair.copy();
                    }
                }
            }
        }

        protected static class FilePair {
            public FilePath source;
            public FilePath target;

            public void copy()
                throws InterruptedException, IOException
            {
                this.source.copyTo(this.target);
            }
        }

        public FilePair getSourceAndTarget(FilePath targetDir, AbstractBuild build, BuildListener listener, String name)
        {
            //TODO: Figure out how to use env vars in the paths. build.getEnvironment(listener).expand(this.file) didn't work.
            FilePath workspace = build.getWorkspace();

            if (workspace == null) {
                return null;
            }
            //TODO: Use build.getEnvironment(listener).expand(...) to expand env vars in the path.
            //TODO: May need to do it directly on this.file, early on.
            //
            //TODO: Enforce it is within the workspace.

            FilePair pair = new FilePair();
            pair.source = workspace.child(name);
            pair.target = this.getTargetPath(targetDir, name);
            return pair;

        }

        public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener)
            throws InterruptedException, IOException
        {
            if(this.association == Association.PROJECT_ONLY || this.association == Association.BOTH) {
                this.archiveProjectFiles(build, listener);
            }
            if(this.association == Association.BUILD_ONLY || this.association == Association.BOTH) {
                this.archiveBuildFiles(build, listener);
                build.addAction(new HtmlAction(build));
            }

            return true;
        }

        public Collection<? extends Action> getProjectActions(AbstractProject project)
        {
            ArrayList<Action> actions = new ArrayList<Action>();
            actions.add(new HtmlAction(project));
            return actions;
        }


        /**
         * An action for the publisher. Instances of Action returned by <getProjectActions>
         * can show up in the Project/Job top-page. The <getIconFileName>, <getDisplayName>,
         * and <getUrlName> are used to add an item for the action to the left-hand
         * menu in the Job's page. 
         *
         * By extending <ProminentProjectAction>, we are added to the table/list of
         * prominent links at the top of the Job's page as well.
         *
         * If <getIconFileName> returns null, we aren't added to either location.
         *
         * The work of actually embedding it in the job's main page is done in the HtmlAction/jobMain.jelly
         * file in the resources. That calls through to this classe's {@link getTitle} and {@link getHtml}
         * methods.
         */
        public class HtmlAction implements Action
        {
            public HtmlAction(AbstractProject project)
            {
                this.fProject = project;
                this.fTargetDir = EmbedReportPublisher.Target.this.getProjectTargetDir(project);
                this.fForBuild = false;
            }

            public HtmlAction(AbstractBuild build)
            {
                this.fProject = build.getProject();
                this.fTargetDir = EmbedReportPublisher.Target.this.getBuildTargetDir(build);
                this.fForBuild = true;
            }

            protected boolean fForBuild;
            protected AbstractProject fProject;
            protected FilePath fTargetDir;

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
                EmbedReportPublisher.Target self = EmbedReportPublisher.Target.this;
                if(self == null) {
                    return null;
                }
                return "embed-" + name;
            }

            /**
             * The title for the section in which the report is embedded on the main page.
             */
            public String getTitle() {
                return EmbedReportPublisher.Target.this.getName();
            }

            public String getUrl() {
                return this.getUrlName();
            }

            /**
             * The style to attach to the iframe from which the report is served when embedded.
             * Used by {@link getHtml}.
             */
            public String getInlineStyle() {
                return "width: 95%; border: 1px solid #666; height: " + EmbedReportPublisher.Target.this.getHeight() + "px;";
            }
            
            public boolean reportReady() throws IOException, InterruptedException {
                return (this.fTargetDir != null && this.fTargetDir.exists());
            }

            public boolean embedReport()
            {
                if(this.fForBuild)
                {
                    return EmbedReportPublisher.Target.this.association == EmbedReportPublisher.Target.Association.BUILD_ONLY
                        || EmbedReportPublisher.Target.this.association == EmbedReportPublisher.Target.Association.BOTH;
                }
                return EmbedReportPublisher.Target.this.association == EmbedReportPublisher.Target.Association.PROJECT_ONLY
                    || EmbedReportPublisher.Target.this.association == EmbedReportPublisher.Target.Association.BOTH;
            }

            /**
             * Return the HTML that is embedded in the job main page by the HtmlAction/jobMain.jelly file.
             */
            public String getHtml() throws IOException, InterruptedException
            {
                if(this.embedReport()) {
                    if(this.reportReady()) {
                        return "<a href='" + this.getUrl() + "' title='View report'>view</a><br />\n"
                            + "<iframe style='" + this.getInlineStyle() + "' src='" + this.getUrl() + "'></iframe>";
                    }
                    return "<p class='no-report'>Report not generated.</p>";
                }
                return "";
            }

            /**
             * Serves the URL subspace specifed by <getUrlName>.
             */
            public void doDynamic(StaplerRequest req, StaplerResponse rsp) throws IOException, InterruptedException, ServletException {
                if(this.reportReady()) {
                    DirectoryBrowserSupport dbs = new DirectoryBrowserSupport(
                        this, this.fTargetDir, EmbedReportPublisher.Target.this.getName(), "graph.gif", false
                    );
                    dbs.setIndexFileName(EmbedReportPublisher.Target.this.getFile());
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
     * page. See, for instance, {@link HtmlAction}.
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

