package org.jenkinsci.plugins.googleplayandroidpublisher;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.jenkins.plugins.credentials.oauth.GoogleRobotCredentials;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractDescribableImpl;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Descriptor;
import hudson.model.Result;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Publisher;
import hudson.util.ComboBoxModel;
import hudson.util.FormValidation;
import net.sf.json.JSONObject;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.export.Exported;

import java.io.IOException;
import java.io.PrintStream;
import java.io.Serializable;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static hudson.Util.fixEmptyAndTrim;
import static hudson.Util.join;
import static hudson.Util.tryParseNumber;
import static org.jenkinsci.plugins.googleplayandroidpublisher.ReleaseTrack.PRODUCTION;
import static org.jenkinsci.plugins.googleplayandroidpublisher.ReleaseTrack.fromConfigValue;
import static org.jenkinsci.plugins.googleplayandroidpublisher.ReleaseTrack.getConfigValues;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Util.REGEX_LANGUAGE;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Util.REGEX_VARIABLE;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Util.SUPPORTED_LANGUAGES;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Util.expand;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Util.getApplicationId;
import static org.jenkinsci.plugins.googleplayandroidpublisher.Util.getVersionCode;

/** Uploads Android application files to the Google Play Developer Console. */
public class ApkPublisher extends GooglePlayPublisher {

    /** Expansion file type: main */
    public static final String TYPE_MAIN = "main";
    /** Expansion file type: patch */
    public static final String TYPE_PATCH = "patch";

    public static final DecimalFormat PERCENTAGE_FORMATTER = new DecimalFormat("#.#");

    /** Allowed percentage values when doing a staged rollout to production. */
    private static final double[] ROLLOUT_PERCENTAGES = { 0.5, 1, 5, 10, 20, 50, 100 };
    private static final double DEFAULT_PERCENTAGE = 100;

    /** File name pattern which expansion files must match. */
    private static final Pattern OBB_FILE_REGEX =
            Pattern.compile("^(main|patch)\\.([0-9]+)\\.([._a-z0-9]+)\\.obb$", Pattern.CASE_INSENSITIVE);

    @DataBoundSetter
    private String apkFilesPattern;

    @DataBoundSetter
    private String expansionFilesPattern;

    @DataBoundSetter
    private boolean usePreviousExpansionFilesIfMissing;

    @DataBoundSetter
    private String trackName;

    @DataBoundSetter
    private String rolloutPercentage;

    @DataBoundSetter
    private RecentChanges[] recentChangeList;

    @DataBoundConstructor
    public ApkPublisher() {}

    public String getApkFilesPattern() {
        return fixEmptyAndTrim(apkFilesPattern);
    }

    private String getExpandedApkFilesPattern(EnvVars env) {
        return expand(env, getApkFilesPattern());
    }

    public String getExpansionFilesPattern() {
        return fixEmptyAndTrim(expansionFilesPattern);
    }

    private String getExpandedExpansionFilesPattern(EnvVars env) {
        return expand(env, getExpansionFilesPattern());
    }

    public boolean getUsePreviousExpansionFilesIfMissing() {
        return usePreviousExpansionFilesIfMissing;
    }

    public String getTrackName() {
        return fixEmptyAndTrim(trackName);
    }

    private String getCanonicalTrackName(EnvVars env) {
        String name = expand(env, getTrackName());
        if (name == null) {
            return null;
        }
        return name.toLowerCase(Locale.ENGLISH);
    }

    public String getRolloutPercentage() {
        return fixEmptyAndTrim(rolloutPercentage);
    }

    private double getRolloutPercentageValue(EnvVars env) {
        String pct = getRolloutPercentage();
        if (pct != null) {
            // Allow % characters in the config
            pct = pct.replace("%", "");
        }
        // If no valid numeric value was set, we will roll out to 100%
        return tryParseNumber(expand(env, pct), DEFAULT_PERCENTAGE).doubleValue();
    }

    public RecentChanges[] getRecentChangeList() {
        return recentChangeList;
    }

    private RecentChanges[] getExpandedRecentChangesList(EnvVars env) {
        if (recentChangeList == null) {
            return null;
        }
        RecentChanges[] expanded = new RecentChanges[recentChangeList.length];
        for (int i = 0; i < recentChangeList.length; i++) {
            RecentChanges r = recentChangeList[i];
            expanded[i] = new RecentChanges(expand(env, r.language), expand(env, r.text));
        }
        return expanded;
    }

    private boolean isConfigValid(PrintStream logger, EnvVars env) {
        final List<String> errors = new ArrayList<String>();

        // Check whether a file pattern was provided
        if (getExpandedApkFilesPattern(env) == null) {
            errors.add("Path or pattern to APK file was not specified");
        }

        // Track name is also required
        final String trackName = getCanonicalTrackName(env);
        final ReleaseTrack track = fromConfigValue(trackName);
        if (trackName == null) {
            errors.add("Release track was not specified");
        } else if (track == null) {
            errors.add(String.format("'%s' is not a valid release track", trackName));
        } else if (track == PRODUCTION) {
            // Check for valid rollout percentage
            double pct = getRolloutPercentageValue(env);
            if (Arrays.binarySearch(ROLLOUT_PERCENTAGES, pct) < 0) {
                errors.add(String.format("%s%% is not a valid rollout percentage", PERCENTAGE_FORMATTER.format(pct)));
            }
        }

        // Print accumulated errors
        if (!errors.isEmpty()) {
            logger.println("Cannot upload to Google Play:");
            for (String error : errors) {
                logger.print("- ");
                logger.println(error);
            }
        }

        return errors.isEmpty();
    }

    @Override
    public boolean perform(AbstractBuild build, Launcher launcher,
            BuildListener listener) throws IOException, InterruptedException {
        PrintStream logger = listener.getLogger();

        // Check whether we should execute at all
        final Result buildResult = build.getResult();
        if (buildResult != null && buildResult.isWorseThan(Result.UNSTABLE)) {
            logger.println("Skipping upload to Google Play due to build result");
            return true;
        }

        // Check that the job has been configured correctly
        final EnvVars env = build.getEnvironment(listener);
        if (!isConfigValid(logger, env)) {
            return false;
        }

        // Find the APK filename(s) which match the pattern after variable expansion
        final String filesPattern = getExpandedApkFilesPattern(env);
        final FilePath ws = build.getWorkspace();
        List<String> relativePaths = ws.act(new FindFilesTask(filesPattern));
        if (relativePaths.isEmpty()) {
            logger.println(String.format("No APK files matching the pattern '%s' could be found", filesPattern));
            return false;
        }

        final ListMultimap<String, Application> apkMultiMap = ArrayListMultimap.create();
        for (String path : relativePaths) {
            // Get the full remote path in the workspace for each filename
            FilePath apk = ws.child(path);
            String applicationId = getApplicationId(apk);
            int versionCode = getVersionCode(apk);
            apkMultiMap.put(applicationId, new Application(applicationId, apk, versionCode));
        }

        for (String applicationId : apkMultiMap.keySet()) {
            final List<Application> apks = apkMultiMap.get(applicationId);

            final List<FilePath> apkFiles = new ArrayList<FilePath>();
            final Set<Integer> versionCodes = new TreeSet<Integer>();
            for (Application app : apks) {
                apkFiles.add(app.apkPath);
                versionCodes.add(app.versionCode);
            }

            // Find the expansion filename(s) which match the pattern after variable expansion
            final Map<Integer, ExpansionFileSet> expansionFiles = new TreeMap<Integer, ExpansionFileSet>();
            final String expansionPattern = getExpandedExpansionFilesPattern(env);
            if (expansionPattern != null) {
                List<String> expansionPaths = ws.act(new FindFilesTask(expansionPattern));

                // Check that the expansion files found apply to the APKs to be uploaded
                for (String path : expansionPaths) {
                    FilePath file = ws.child(path);

                    // Check that the filename is in the right format
                    Matcher matcher = OBB_FILE_REGEX.matcher(file.getName());
                    if (!matcher.matches()) {
                        logger.println(String.format("Expansion file '%s' doesn't match the required naming scheme", path));
                        return false;
                    }

                    // We can only associate expansion files with the application ID we're going to upload
                    final String appId = matcher.group(3);
                    if (!applicationId.equals(appId)) {
                        continue;
                    }

                    // We can only associate expansion files with version codes we're going to upload
                    final int versionCode = Integer.parseInt(matcher.group(2));
                    if (!versionCodes.contains(versionCode)) {
                        continue;
                    }

                    // File looks good, so add it to the fileset for this version code
                    final String type = matcher.group(1).toLowerCase(Locale.ENGLISH);
                    ExpansionFileSet fileSet = expansionFiles.get(versionCode);
                    if (fileSet == null) {
                        fileSet = new ExpansionFileSet();
                        expansionFiles.put(versionCode, fileSet);
                    }
                    if (type.equals(TYPE_MAIN)) {
                        fileSet.setMainFile(file);
                    } else {
                        fileSet.setPatchFile(file);
                    }
                }

                // If there are patch files, make sure that each has a main file, or "use previous if missing" is enabled
                for (ExpansionFileSet fileSet : expansionFiles.values()) {
                    if (!usePreviousExpansionFilesIfMissing && fileSet.getPatchFile() != null && fileSet.getMainFile() == null) {
                        logger.println(String.format("Patch expansion file '%s' was provided, but no main expansion file "
                                + "was provided, and the option to reuse a pre-existing expansion file was "
                                + "disabled.\nGoogle Play requires that each APK with a patch file also has a main " + "file.", fileSet
                                .getPatchFile().getName()));
                        return false;
                    }
                }
            }

            // Upload the file(s) from the workspace
            try {
                GoogleRobotCredentials credentials = getServiceAccountCredentials();
                boolean res = build.getWorkspace().act(
                        new ApkUploadTask(listener, credentials, applicationId, apkFiles, expansionFiles,
                                usePreviousExpansionFilesIfMissing, fromConfigValue(getCanonicalTrackName(env)),
                                getRolloutPercentageValue(env), getExpandedRecentChangesList(env)));
                if (!res) {
                    return false;
                }
            } catch (UploadException e) {
                logger.println(String.format("Upload failed: %s", getPublisherErrorMessage(e)));
                logger.println("- No changes have been applied to the Google Play account");
            }
        }
        return true;
    }

    private static class Application {
        public final String applicationId;
        public final FilePath apkPath;
        public final int versionCode;

        private Application(String applicationId, FilePath apkPath, int versionCode) {
            this.applicationId = applicationId;
            this.apkPath = apkPath;
            this.versionCode = versionCode;
        }
    }

    static final class ExpansionFileSet implements Serializable {

        private static final long serialVersionUID = 1;

        FilePath mainFile;
        FilePath patchFile;

        public FilePath getMainFile() {
            return mainFile;
        }

        public void setMainFile(FilePath mainFile) {
            this.mainFile = mainFile;
        }

        public FilePath getPatchFile() {
            return patchFile;
        }

        public void setPatchFile(FilePath patchFile) {
            this.patchFile = patchFile;
        }

    }

    public static final class RecentChanges extends AbstractDescribableImpl<RecentChanges> implements Serializable {

        private static final long serialVersionUID = 1;

        @Exported
        public final String language;

        @Exported
        public final String text;

        @DataBoundConstructor
        public RecentChanges(String language, String text) {
            this.language = language;
            this.text = text;
        }

        @Extension
        public static class DescriptorImpl extends Descriptor<RecentChanges> {

            @Override
            public String getDisplayName() {
                return null;
            }

            public ComboBoxModel doFillLanguageItems() {
                return new ComboBoxModel(SUPPORTED_LANGUAGES);
            }

            public FormValidation doCheckLanguage(@QueryParameter String value) {
                value = fixEmptyAndTrim(value);
                if (value != null && !value.matches(REGEX_LANGUAGE) && !value.matches(REGEX_VARIABLE)) {
                    return FormValidation.warning("Should be a language code like 'be' or 'en-GB'");
                }
                return FormValidation.ok();
            }

            public FormValidation doCheckText(@QueryParameter String value) {
                value = fixEmptyAndTrim(value);
                if (value != null && value.length() > 500) {
                    return FormValidation.error("Recent changes text must be 500 characters or fewer");
                }
                return FormValidation.ok();
            }

        }

    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        public DescriptorImpl() {
            load();
        }

        public String getDisplayName() {
            return "Upload Android APK to Google Play";
        }

        public FormValidation doCheckApkFiles(@QueryParameter String value) {
            if (fixEmptyAndTrim(value) == null) {
                return FormValidation.error("An APK file path or pattern is required");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckTrackName(@QueryParameter String value) {
            if (fixEmptyAndTrim(value) == null) {
                return FormValidation.error("A release track is required");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckRolloutPercentage(@QueryParameter String value) {
            value = fixEmptyAndTrim(value);
            if (value == null || value.matches(REGEX_VARIABLE)) {
                return FormValidation.ok();
            }

            final double lowest = ROLLOUT_PERCENTAGES[0];
            final double highest = DEFAULT_PERCENTAGE;
            double pct = tryParseNumber(value.replace("%", ""), highest).doubleValue();
            if (Double.compare(pct, 0.5) < 0 || Double.compare(pct, DEFAULT_PERCENTAGE) > 0) {
                return FormValidation.error("Percentage value must be between %s and %s%%",
                        PERCENTAGE_FORMATTER.format(lowest), PERCENTAGE_FORMATTER.format(highest));
            }
            return FormValidation.ok();
        }

        public ComboBoxModel doFillTrackNameItems() {
            return new ComboBoxModel(getConfigValues());
        }

        public ComboBoxModel doFillRolloutPercentageItems() {
            ComboBoxModel list = new ComboBoxModel();
            for (double pct : ROLLOUT_PERCENTAGES) {
                list.add(String.format("%s%%", PERCENTAGE_FORMATTER.format(pct)));
            }
            return list;
        }

        public boolean isApplicable(Class<? extends AbstractProject> c) {
            return true;
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            save();
            return super.configure(req, formData);
        }

    }

}
