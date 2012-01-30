package hudson.scm;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.matrix.Axis;
import hudson.matrix.AxisList;
import hudson.matrix.MatrixRun;
import hudson.matrix.MatrixBuild;
import hudson.matrix.MatrixProject;
import hudson.maven.MavenModuleSet;
import hudson.maven.MavenModuleSetBuild;
import hudson.model.FreeStyleBuild;
import hudson.model.Result;
import hudson.model.Cause;
import hudson.model.FreeStyleProject;
import hudson.model.ParametersAction;
import hudson.model.StringParameterValue;
import hudson.model.labels.LabelAtom;
import hudson.scm.CvsModuleLocation.BranchModuleLocation;
import hudson.scm.CvsModuleLocation.HeadModuleLocation;
import hudson.scm.CvsModuleLocation.TagModuleLocation;
import hudson.scm.browsers.ViewCVS;
import hudson.slaves.DumbSlave;
import hudson.util.StreamTaskListener;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;


import org.jvnet.hudson.test.Bug;
import org.jvnet.hudson.test.Email;
import org.jvnet.hudson.test.HudsonTestCase;
import org.jvnet.hudson.test.CaptureEnvironmentBuilder;

/**
 * @author Kohsuke Kawaguchi
 */
public class CVSSCMTest extends HudsonTestCase {
    /**
     * Verifies that there's no data loss.
     */
    
      //TODO: fix config roundtrip
    @SuppressWarnings("deprecation")
    public void testConfigRoundtrip() throws Exception {
        FreeStyleProject p = createFreeStyleProject();

        // verify values
        CVSSCM scm1 = new CVSSCM("cvsroot", "module", "branch", "cvsRsh", true,
                        true, true, true, "excludedRegions");
        p.setScm(scm1);
        roundtrip(p);
        assertEquals(scm1, (CVSSCM) p.getScm());

        // all boolean fields need to be tried with two values
        scm1 = new CVSSCM("x", "y", "z", "w", false, false, false, false, "t");
        p.setScm(scm1);

        roundtrip(p);
        assertEquals(scm1, (CVSSCM) p.getScm());
    }

    public void testUpgradeParameters() {
        CvsModuleLocation location = new CvsModuleLocation.HeadModuleLocation();
        CvsModule[] modules = new CvsModule[3];
        modules[0] = new CvsModule("module1", "", location);
        modules[1] = new CvsModule("module2", "", location);
        modules[2] = new CvsModule("module 3", "", location);
        CvsRepository[] repositories = new CvsRepository[1];
        repositories[0] = new CvsRepository("cvsroot", false, null, Arrays.asList(modules),
                        Arrays.asList(new ExcludedRegion[] {
                                        new ExcludedRegion("excludedRegions"),
                                        new ExcludedRegion("region2") }), -1);

        @SuppressWarnings("deprecation")
        CVSSCM scm1 = new CVSSCM("cvsroot", "module1 module2 module\\ 3", "",
                        "cvsRsh", true, false, true, false,
                        "excludedRegions\rregion2");
        assertEquals("Unexpected number of repositories", 1, scm1.getRepositories().length);
        assertEquals("Unexpected number of modules", 3, scm1.getRepositories()[0].getModules().length);
        for (int i = 0; i < repositories.length; i++) {
            assertEquals(repositories[i], scm1.getRepositories()[i]);
        }

    }

    @Bug(4456)
    public void testGlobalConfigRoundtrip() throws Exception {
        CVSSCM.DescriptorImpl d = hudson
                        .getDescriptorByType(CVSSCM.DescriptorImpl.class);
        d.setCompressionLevel(1);

        submit(createWebClient().goTo("configure").getFormByName("config"));
        assertEquals(1, d.getCompressionLevel());
    }

    private void roundtrip(final FreeStyleProject p) throws Exception {
        submit(new WebClient().getPage(p, "configure").getFormByName("config"));
    }

    private void assertEquals(final CVSSCM scm1, final CVSSCM scm2) {
        assertEquals(scm1.isCanUseUpdate(), scm2.isCanUseUpdate());
        assertEquals(scm1.isFlatten(), scm2.isFlatten());
        assertEquals(scm1.getRepositories().length,
                        scm2.getRepositories().length);
        for (int i = 0; i < scm1.getRepositories().length; i++) {
            assertEquals(scm1.getRepositories()[i], scm2.getRepositories()[i]);
        }
    }

    @Email("https://hudson.dev.java.net/servlets/BrowseList?list=users&by=thread&from=2222483")
    @Bug(4760)
    public void testProjectExport() throws Exception {
        FreeStyleProject p = createFreeStyleProject();
        assertBuildStatusSuccess(p.scheduleBuild2(0).get());
        @SuppressWarnings("deprecation")
        CVSSCM scm = new CVSSCM(":pserver:nowhere.net/cvs/foo", ".", null, null, true, false, true, false, null);
        p.setScm(scm);
        Field repositoryBrowser = scm.getClass().getDeclaredField("repositoryBrowser");
        repositoryBrowser.setAccessible(true);
        repositoryBrowser.set(scm, new ViewCVS(new URL("http://nowhere.net/viewcvs/")));
        new WebClient().goTo(p.getUrl() + "api/xml", "application/xml");
        new WebClient().goTo(p.getUrl() + "api/xml?depth=999", "application/xml");
    }
    
    /* New tests for CVS plug-in version 2.0 */
    
    public void testLegacyConverter() throws Exception {
        String cvsRoot = "test_cvsroot";
        String module = "test_module";
        String branch = "test_branch";
        String cvsRsh = "test_cvsRsh";
        String excludedRegion = "test_excludedRegion";
        boolean canUseUpdate = false;
        boolean useHeadIfNoTag = false;
        boolean legacy = false;
        boolean isTag = false;
        
        CVSSCM scm1 = new CVSSCM(cvsRoot, module, branch, cvsRsh, canUseUpdate, useHeadIfNoTag, legacy, isTag, excludedRegion);
        CvsRepository cvsRepository = scm1.getRepositories()[0];
        
        assertEquals(cvsRoot, cvsRepository.getCvsRoot());
        CvsModule cvsModule = cvsRepository.getModules()[0];
        assertEquals(module, cvsModule.getRemoteName());
        assertEquals(branch, cvsModule.getModuleLocation().getLocationName());

        assertEquals(canUseUpdate, scm1.isCanUseUpdate());
        assertEquals(useHeadIfNoTag, cvsModule.getModuleLocation().isUseHeadIfNotFound());
        assertEquals(legacy, scm1.isLegacy());
        assertTrue(cvsModule.getModuleLocation() instanceof BranchModuleLocation);
        assertEquals(excludedRegion, cvsRepository.getExcludedRegions()[0].getPattern());
        
        //cvsRsh is not used anymore
        
        canUseUpdate = true;
        useHeadIfNoTag = true;
        legacy = true;
        isTag = true;
        
        CVSSCM scm2 = new CVSSCM(cvsRoot, module, branch, cvsRsh, canUseUpdate, useHeadIfNoTag, legacy, isTag, excludedRegion);
        
        assertEquals(canUseUpdate, scm2.isCanUseUpdate());
        CvsRepository cvsRepository2 = scm2.getRepositories()[0];
        CvsModule cvsModule2 = cvsRepository2.getModules()[0];
        assertEquals(useHeadIfNoTag, cvsModule2.getModuleLocation().isUseHeadIfNotFound());
        assertEquals(legacy, scm2.isLegacy());
        assertTrue(cvsModule2.getModuleLocation() instanceof TagModuleLocation);
    }

    /* this settings are hard coded for now and will be replaced by mocks later*/
    
    String local_test_cvsroot = ":pserver:cvsuser:cvs@localhost:/var/lib/cvs";
    String local_test_cvsroot2 = ":pserver:cvsuser:cvs@localhost:/var/lib/cvs";
    String local_test_module = "fooModule";
    String local_test_module2 = "barModule";
    String local_test_tag = "fooTag";
    String local_test_branch = "fooBranch";
    String local_test_branch2 = "barBranch";
    
    String local_test_headFile = "head.txt";
    String local_test_headFile2 = "barHead.txt";
    String local_test_tagFile = "tag.txt";
    String local_test_tagFile2 = "barTag.txt";
    String local_test_branchFile = "branch.txt";
    String local_test_branchFile2 = "barBranch.txt";
    
    String nonExistingTag = "wrongTag";
    String singleFileTag = "singleTag";
    
    //# Single Module, Single Repo
    
    private CVSSCM createSingleModuleSingleRepoCvsScm(String cvsRoot, String moduleName, CvsModuleLocation moduleLocation, String localName, boolean legacy) throws Exception {
        CvsModule cvsModule = new CvsModule(moduleName, localName, moduleLocation);
        List<ExcludedRegion> regions = Collections.emptyList();
        CvsRepository cvsRepo = new CvsRepository(cvsRoot, false, null, Collections.singletonList(cvsModule), regions, 3);
        return new CVSSCM(Collections.singletonList(cvsRepo), false, legacy, null, false, false, false);
        
    }
    
    private CVSSCM createSingleModuleSingleRepoCvsScmHead() throws Exception {
        return createSingleModuleSingleRepoCvsScm(local_test_cvsroot, local_test_module, new HeadModuleLocation(), null, false);
    }

    private CVSSCM createSingleModuleSingleRepoCvsScmTag() throws Exception {
        return createSingleModuleSingleRepoCvsScm(local_test_cvsroot, local_test_module, new TagModuleLocation(local_test_tag, false), null, false);
    }

    private CVSSCM createSingleModuleSingleRepoCvsScmBranch() throws Exception {
        return createSingleModuleSingleRepoCvsScm(local_test_cvsroot, local_test_module, new BranchModuleLocation(local_test_branch, false), null, false);
    }

    //# Multi Module, Single Repo
    
    private CVSSCM createMultiModuleSingleRepoCvsScm(String cvsRoot, String moduleName1, String moduleName2, CvsModuleLocation moduleLocation1, CvsModuleLocation moduleLocation2) throws Exception {
        CvsModule cvsModule1 = new CvsModule(moduleName1, null, moduleLocation1);
        CvsModule cvsModule2 = new CvsModule(moduleName2, null, moduleLocation2);
        List<ExcludedRegion> regions = Collections.emptyList();
        List<CvsModule> moduleList = new ArrayList<CvsModule>();
        moduleList.add(cvsModule1);
        moduleList.add(cvsModule2);
        CvsRepository cvsRepo = new CvsRepository(cvsRoot, false, null, moduleList, regions, 3);
        return new CVSSCM(Collections.singletonList(cvsRepo), false, false, null, false, false, false);
    }

    //# Multi Module, Multi Repo
    
    private CVSSCM createMultiModuleMultiRepoCvsScm(String cvsRoot1, String moduleName1, CvsModuleLocation moduleLocation1, String cvsRoot2, String moduleName2, CvsModuleLocation moduleLocation2) throws Exception {
        CvsModule cvsModule1 = new CvsModule(moduleName1, null, moduleLocation1);
        CvsModule cvsModule2 = new CvsModule(moduleName2, null, moduleLocation2);
        List<ExcludedRegion> regions = Collections.emptyList();
        CvsRepository cvsRepo = new CvsRepository(cvsRoot1, false, null, Collections.singletonList(cvsModule1), regions, 3);
        CvsRepository cvsRepo2 = new CvsRepository(cvsRoot2, false, null, Collections.singletonList(cvsModule2), regions, 3);
        List<CvsRepository> cvsRepos = new ArrayList<CvsRepository>();
        cvsRepos.add(cvsRepo);
        cvsRepos.add(cvsRepo2);
        return new CVSSCM(cvsRepos, false, false, null, false, false, false);
    }

    private void checkHeadTagBranch(FreeStyleProject p) throws IOException, Exception, InterruptedException, ExecutionException {
        CaptureEnvironmentBuilder builder = new CaptureEnvironmentBuilder();
        p.getBuildersList().add(builder);
        
        //HEAD
        p.setScm(createSingleModuleSingleRepoCvsScmHead());
        //verify that build was successful
        FreeStyleBuild b = assertBuildStatusSuccess(p.scheduleBuild2(0).get());
        //should be a file that only exists in HEAD version
        assertTrue(b.getWorkspace().child(local_test_headFile).exists());
        assertEquals(null, builder.getEnvVars().get("CVS_BRANCH"));
        
        //TAG
        p.setScm(createSingleModuleSingleRepoCvsScmTag());
        //verify that build was successful
        b = assertBuildStatusSuccess(p.scheduleBuild2(0).get());
        //should be a file that only exists in tag version 
        assertTrue(b.getWorkspace().child(local_test_tagFile).exists());
        assertEquals(local_test_tag, builder.getEnvVars().get("CVS_BRANCH"));
        
        //BRANCH
        p.setScm(createSingleModuleSingleRepoCvsScmBranch());
        //verify that build was successful
        b = assertBuildStatusSuccess(p.scheduleBuild2(0).get());
        //should be a file that only exists in branch version 
        
        assertTrue(b.getWorkspace().child(local_test_branchFile).exists());
        assertEquals(local_test_branch, builder.getEnvVars().get("CVS_BRANCH"));
    }
    
    
    public void testCheckoutFreestyleProject() throws Exception{
        FreeStyleProject p = createFreeStyleProject(); 
        checkHeadTagBranch(p);
    }

    public void testCheckoutFreestyleProjectRemote() throws Exception{
        DumbSlave s = createSlave();
        FreeStyleProject p = createFreeStyleProject();
        p.setAssignedLabel(s.getSelfLabel());
        checkHeadTagBranch(p);
    }
    
    private void checkMatrixRuns(MatrixBuild b, String file, String envVar) throws Exception {
        for(MatrixRun r : b.getRuns()){
            assertTrue(r.getResult().equals(Result.SUCCESS));
            assertTrue(r.getWorkspace().child(file).exists());
            assertEquals(envVar, r.getEnvironment(new StreamTaskListener(System.out, Charset.defaultCharset())).get("CVS_BRANCH"));
        }
    }
    
    /**
     * This test also covers NumberFormatExceptions that used to appear when
     * running two simultaneous CVS checkouts on the same machine at the same time.<br/>
     * This concurrency issue has been fixed in the NetBeans CVS client library 
     * 
     * 
     * @throws Exception
     */
    public void testCheckoutMatrixProject() throws Exception{
        MatrixProject p = createMatrixProject();
        p.setScm(createSingleModuleSingleRepoCvsScmHead());
        
        CaptureEnvironmentBuilder builder = new CaptureEnvironmentBuilder();
        p.getBuildersList().add(builder);
        
        p.setAxes(new AxisList(Collections.singletonList(new Axis("MainAxis", "foo", "bar"))));
        
        //HEAD
        p.setScm(createSingleModuleSingleRepoCvsScmHead());
        //verify that build was successful
        MatrixBuild b = assertBuildStatusSuccess(p.scheduleBuild2(0).get());
        //should be a file that only exists in HEAD version
        assertTrue(b.getWorkspace().child(local_test_headFile).exists());
        assertEquals(null, builder.getEnvVars().get("CVS_BRANCH"));
        checkMatrixRuns(b, local_test_headFile, null);
        
        //TAG
        p.setScm(createSingleModuleSingleRepoCvsScmTag());
        //verify that build was successful
        b = assertBuildStatusSuccess(p.scheduleBuild2(0).get());
        //should be a file that only exists in tag version 
        assertTrue(b.getWorkspace().child(local_test_tagFile).exists());
        assertEquals(local_test_tag, builder.getEnvVars().get("CVS_BRANCH"));
        checkMatrixRuns(b, local_test_tagFile, local_test_tag);
        
        //BRANCH
        p.setScm(createSingleModuleSingleRepoCvsScmBranch());
        //verify that build was successful
        b = assertBuildStatusSuccess(p.scheduleBuild2(0).get());
        //should be a file that only exists in branch version 
        assertTrue(b.getWorkspace().child(local_test_branchFile).exists());
        assertEquals(local_test_branch, builder.getEnvVars().get("CVS_BRANCH"));
        checkMatrixRuns(b, local_test_branchFile, local_test_branch);
    }
    
    //TODO: test matrix project with slaves

    public void testCheckoutMavenModuleSet() throws Exception{
        configureDefaultMaven();
        MavenModuleSet p = createMavenProject();
        
        p.setScm(createSingleModuleSingleRepoCvsScmHead());
        
        //HEAD
        p.setScm(createSingleModuleSingleRepoCvsScmHead());
        //verify that build was successful
        MavenModuleSetBuild b = assertBuildStatusSuccess(p.scheduleBuild2(0).get());
        //should be a file that only exists in HEAD version
        assertTrue(b.getWorkspace().child(local_test_headFile).exists());
        assertEquals(null, b.getEnvironment(new StreamTaskListener(System.out, Charset.defaultCharset())).get("CVS_BRANCH"));
        
        //TAG
        p.setScm(createSingleModuleSingleRepoCvsScmTag());
        //verify that build was successful
        b = assertBuildStatusSuccess(p.scheduleBuild2(0).get());
        //should be a file that only exists in tag version 
        assertTrue(b.getWorkspace().child(local_test_tagFile).exists());
        assertEquals(local_test_tag, b.getEnvironment(new StreamTaskListener(System.out, Charset.defaultCharset())).get("CVS_BRANCH"));
        
        //BRANCH
        p.setScm(createSingleModuleSingleRepoCvsScmBranch());
        //verify that build was successful
        b = assertBuildStatusSuccess(p.scheduleBuild2(0).get());
        //should be a file that only exists in branch version 
        assertTrue(b.getWorkspace().child(local_test_branchFile).exists());
        assertEquals(local_test_branch, b.getEnvironment(new StreamTaskListener(System.out, Charset.defaultCharset())).get("CVS_BRANCH"));
    }
    
    
    /**
     * Test what happens if a tag does not exist and how "Use Head If Tag Does Not Exist" works
     * 
     * @throws Exception
     */
    public void testCheckoutTagUseHeadIfTagDoesNotExist() throws Exception{
        FreeStyleProject p = createFreeStyleProject();
        
        CaptureEnvironmentBuilder builder = new CaptureEnvironmentBuilder();
        p.getBuildersList().add(builder);

        //#Non-existing Tag - Use Head If Tag Does Not Exist = false
        p.setScm(createSingleModuleSingleRepoCvsScm(local_test_cvsroot, local_test_module, new TagModuleLocation(nonExistingTag, false), null, false));
        
        //verify that build fails
        assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0).get());

        //#Non-existing Tag - Use Head If Tag Does Not Exist = true
        p.setScm(createSingleModuleSingleRepoCvsScm(local_test_cvsroot, local_test_module, new TagModuleLocation(nonExistingTag, true), null, false));
        
        //verify that build fails
        assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0).get());
        
        //#Tag that only exists for one file - Use Head If Tag Does Not Exist = false
        p.setScm(createSingleModuleSingleRepoCvsScm(local_test_cvsroot, local_test_module, new TagModuleLocation(singleFileTag, false), null, false));
        
        //verify that build fails
        assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0).get());
        
        //#Tag that only exists for one file - Use Head If Tag Does Not Exist = true
        p.setScm(createSingleModuleSingleRepoCvsScm(local_test_cvsroot, local_test_module, new TagModuleLocation(singleFileTag, true), null, false));
        
        //verify that build was successful
        assertBuildStatusSuccess(p.scheduleBuild2(0).get());
        assertEquals(singleFileTag, builder.getEnvVars().get("CVS_BRANCH"));
    }
    
    /* Parameter tests */
    
    @Bug(2318)
    public void testCheckoutTagWithParameter() throws Exception{
        String parameterizedTag = "${cvsTag}";
        
        FreeStyleProject p = createFreeStyleProject();
        p.setScm(createSingleModuleSingleRepoCvsScm(local_test_cvsroot, local_test_module, new TagModuleLocation(parameterizedTag, false), null, false));
        
        CaptureEnvironmentBuilder builder = new CaptureEnvironmentBuilder();
        p.getBuildersList().add(builder);
        
        ParametersAction parametersAction = new ParametersAction(new StringParameterValue("cvsTag", local_test_tag));

        //verify that build was successful
        FreeStyleBuild b = assertBuildStatusSuccess(p.scheduleBuild2(0, new Cause.UserCause(), parametersAction).get());
        
        //should be a file that only exists in tag version 
        assertTrue(b.getWorkspace().child(local_test_tagFile).exists());
        
        assertEquals(local_test_tag, builder.getEnvVars().get("CVS_BRANCH"));
        
        //TODO: what should actually happen when the given tag parameter is empty? (looks like HEAD is getting checked out)
        ParametersAction parametersAction2 = new ParametersAction(new StringParameterValue("cvsTag", ""));
        FreeStyleBuild b2 = assertBuildStatusSuccess(p.scheduleBuild2(0, new Cause.UserCause(), parametersAction2).get());
        for(FilePath fp : b2.getWorkspace().list()){
            System.out.println("FP: " + fp);
        }
        //TODO: shouldn't this be null if the parameter is an empty string?
        assertEquals(null, builder.getEnvVars().get("CVS_BRANCH"));
    }
    
    //TODO: test build parameters in multi module, multi repo setup
    
    public void testCheckoutBranchWithEnvironmentParameter() throws Exception{
        // create slave
        String cvsEnvTestVarName = "CVS_ENV_TEST";
        EnvVars additionalEnv = new EnvVars(cvsEnvTestVarName, local_test_branch);
        DumbSlave slaveEnv = createSlave(new LabelAtom("slaveEnv"), additionalEnv);
        
        FreeStyleProject p = createFreeStyleProject();
        p.setAssignedLabel(slaveEnv.getSelfLabel());
        
        CaptureEnvironmentBuilder builder = new CaptureEnvironmentBuilder();
        p.getBuildersList().add(builder);

        p.setScm(createSingleModuleSingleRepoCvsScm(local_test_cvsroot, local_test_module, new BranchModuleLocation("$"+cvsEnvTestVarName, false), null, false));

        //verify that build was successful
        FreeStyleBuild b = assertBuildStatusSuccess(p.scheduleBuild2(0).get());
        
        //should be a file that only exists in branch version 
        assertTrue(b.getWorkspace().child(local_test_branchFile).exists());
        
        assertEquals(local_test_branch, builder.getEnvVars().get("CVS_BRANCH"));
        
        //TODO: what should actually happen when the given branch parameter is empty?
    }

    //TODO: test environment variables in multi module, multi repo setup
    
    public void testCvsRootWithParameter() throws Exception {
        FreeStyleProject p = createFreeStyleProject();
        p.setScm(createSingleModuleSingleRepoCvsScm("${cvsRoot}", local_test_module, new HeadModuleLocation(), null, false));

        ParametersAction parametersAction = new ParametersAction(new StringParameterValue("cvsRoot", local_test_cvsroot));

        //verify that build was successful
        assertBuildStatusSuccess(p.scheduleBuild2(0, new Cause.UserCause(), parametersAction).get());
    }
    
    
    //TODO: test for multi module checkout (same module, different tags, etc)
    
    public void testCheckoutMultiModuleSingleRepo() throws Exception {
        FreeStyleProject p = createFreeStyleProject();
        
        CaptureEnvironmentBuilder builder = new CaptureEnvironmentBuilder();
        p.getBuildersList().add(builder);
        
        //HEAD
        p.setScm(createMultiModuleSingleRepoCvsScm(local_test_cvsroot, local_test_module, local_test_module2, new HeadModuleLocation(), new HeadModuleLocation()));
        //verify that build was successful
        FreeStyleBuild b = assertBuildStatusSuccess(p.scheduleBuild2(0).get());
        
        assertTrue(b.getWorkspace().child(local_test_module).child(local_test_headFile).exists());
        assertTrue(b.getWorkspace().child(local_test_module2).child(local_test_headFile2).exists());
        
        //TODO: in the future, additional CVS_BRANCH env variables should be used
        assertEquals(null, builder.getEnvVars().get("CVS_BRANCH"));
        
        //TAG
        p.setScm(createMultiModuleSingleRepoCvsScm(local_test_cvsroot, local_test_module, local_test_module2,
                new TagModuleLocation(local_test_tag, false), new TagModuleLocation(local_test_tag, false)));
        
        b = assertBuildStatusSuccess(p.scheduleBuild2(0).get());
        
        assertTrue(b.getWorkspace().child(local_test_module).child(local_test_tagFile).exists());
        assertTrue(b.getWorkspace().child(local_test_module2).child(local_test_tagFile2).exists());
        
        //as long as the same tag is used across all modules, the CVS_BRANCH env var should be filled
        assertEquals(local_test_tag, builder.getEnvVars().get("CVS_BRANCH"));
        
        //BRANCH
        p.setScm(createMultiModuleSingleRepoCvsScm(local_test_cvsroot, local_test_module, local_test_module2,
                new BranchModuleLocation(local_test_branch, false), new BranchModuleLocation(local_test_branch2, false)));
        //verify that build was successful
        b = assertBuildStatusSuccess(p.scheduleBuild2(0).get());
        
        assertTrue(b.getWorkspace().child(local_test_module).child(local_test_branchFile).exists());
        assertTrue(b.getWorkspace().child(local_test_module2).child(local_test_branchFile2).exists());
        
        //when different branches (or tags) are used the CVS_BRANCH env var does not get filled 
        assertEquals(null, builder.getEnvVars().get("CVS_BRANCH"));
    }
    
    public void testCheckoutMultiModuleMultiRepo() throws Exception {
        FreeStyleProject p = createFreeStyleProject();
        
        CaptureEnvironmentBuilder builder = new CaptureEnvironmentBuilder();
        p.getBuildersList().add(builder);
        
        //HEAD
        p.setScm(createMultiModuleMultiRepoCvsScm(local_test_cvsroot, local_test_module, new HeadModuleLocation(),
                local_test_cvsroot2, local_test_module2, new HeadModuleLocation()));
        //verify that build was successful
        FreeStyleBuild b = assertBuildStatusSuccess(p.scheduleBuild2(0).get());
        
        assertTrue(b.getWorkspace().child(local_test_module).exists());
        assertTrue(b.getWorkspace().child(local_test_module2).exists());
        
        //because HEAD is checked out, CVS_BRANCH must be null
        assertEquals(null, builder.getEnvVars().get("CVS_BRANCH"));
        
        //TAG
        p.setScm(createMultiModuleMultiRepoCvsScm(local_test_cvsroot, local_test_module, new TagModuleLocation(local_test_tag, false),
                local_test_cvsroot2, local_test_module2, new TagModuleLocation(local_test_tag, false)));
        //verify that build was successful
        b = assertBuildStatusSuccess(p.scheduleBuild2(0).get());
        
        assertTrue(b.getWorkspace().child(local_test_module).exists());
        assertTrue(b.getWorkspace().child(local_test_module2).exists());
        
        //as long as the same tag is used across all modules, the CVS_BRANCH env var should be filled
        assertEquals(local_test_tag, builder.getEnvVars().get("CVS_BRANCH"));
        
        //TAG
        p.setScm(createMultiModuleMultiRepoCvsScm(local_test_cvsroot, local_test_module, new BranchModuleLocation(local_test_branch, false),
                local_test_cvsroot2, local_test_module2, new BranchModuleLocation(local_test_branch2, false)));
        //verify that build was successful
        b = assertBuildStatusSuccess(p.scheduleBuild2(0).get());
        
        assertTrue(b.getWorkspace().child(local_test_module).exists());
        assertTrue(b.getWorkspace().child(local_test_module2).exists());
        
        //when different branches (or tags) are used the CVS_BRANCH env var does not get filled 
        assertEquals(null, builder.getEnvVars().get("CVS_BRANCH"));
    }
    
    /* Polling tests */
    
    public void testPollingOnMaster() throws Exception {
        FreeStyleProject p = createFreeStyleProject();
        
        //test with single module, single repo
        p.setScm(createSingleModuleSingleRepoCvsScmHead());
        
        assertBuildStatusSuccess(p.scheduleBuild2(0).get());
        
        //initial polling should not find any change
        assertFalse(p.poll(new StreamTaskListener(System.out, Charset.defaultCharset())).hasChanges());
        
        //TODO: create commit
        
        //second polling after change should find changes
        //assertTrue(p.poll(new StreamTaskListener(System.out, Charset.defaultCharset())).hasChanges());
        
        //test with multiple modules, single repo 
        p.setScm(createMultiModuleSingleRepoCvsScm(local_test_cvsroot, local_test_module, local_test_module2, new HeadModuleLocation(), new HeadModuleLocation()));

        assertBuildStatusSuccess(p.scheduleBuild2(0).get());
        
        //initial polling should not find any change
        assertFalse(p.poll(new StreamTaskListener(System.out, Charset.defaultCharset())).hasChanges());
        
        //TODO: create commit to first module
        
        //second polling after change should find changes
        //assertTrue(p.poll(new StreamTaskListener(System.out, Charset.defaultCharset())).hasChanges());

        assertBuildStatusSuccess(p.scheduleBuild2(0).get());
        
        //third polling should not find any change
        assertFalse(p.poll(new StreamTaskListener(System.out, Charset.defaultCharset())).hasChanges());
        
        //TODO: create commit to second module
        
        //fourth polling after change should find changes
        //assertTrue(p.poll(new StreamTaskListener(System.out, Charset.defaultCharset())).hasChanges());
        
        //TODO: test multi module, multi repo polling
    }
    
    public void testPollingOnSlave() throws Exception {
        DumbSlave s = createSlave();
        FreeStyleProject p = createFreeStyleProject();
        p.setAssignedLabel(s.getSelfLabel());

        p.setScm(createSingleModuleSingleRepoCvsScmHead());
        
        assertBuildStatusSuccess(p.scheduleBuild2(0).get());
        
        //initial polling should not find any change
        assertFalse(p.poll(new StreamTaskListener(System.out, Charset.defaultCharset())).hasChanges());
        
        //TODO: create commit
        
        //second polling after change should find changes
        //assertFalse(p.poll(new StreamTaskListener(System.out, Charset.defaultCharset())).hasChanges());
    }

    public void testLocalNameAndLegacyField() throws Exception{
        FreeStyleProject p = createFreeStyleProject();
        
        //no local name, legacy off
        p.setScm(createSingleModuleSingleRepoCvsScmHead());
        FreeStyleBuild b = assertBuildStatusSuccess(p.scheduleBuild2(0).get());
        assertTrue(b.getWorkspace().child(local_test_headFile).exists());
        
        //local name = foobar, legacy off
        String localName = "foobar";
        p.setScm(createSingleModuleSingleRepoCvsScm(local_test_cvsroot, local_test_module, new HeadModuleLocation(), localName, false));
        b = assertBuildStatusSuccess(p.scheduleBuild2(0).get());
        assertFalse(b.getWorkspace().child(local_test_headFile).exists());
        assertTrue(b.getWorkspace().child(localName).child(local_test_headFile).exists());

        //no local name, legacy on
        p.setScm(createSingleModuleSingleRepoCvsScm(local_test_cvsroot, local_test_module, new HeadModuleLocation(), null, true));
        b = assertBuildStatusSuccess(p.scheduleBuild2(0).get());
        assertFalse(b.getWorkspace().child(local_test_headFile).exists());
        assertTrue(b.getWorkspace().child(local_test_module).child(local_test_headFile).exists());

        //local name = foobar, legacy on (local name has a higher priority?)
        p.setScm(createSingleModuleSingleRepoCvsScm(local_test_cvsroot, local_test_module, new HeadModuleLocation(), localName, true));
        b = assertBuildStatusSuccess(p.scheduleBuild2(0).get());
        assertFalse(b.getWorkspace().child(local_test_headFile).exists());
        assertTrue(b.getWorkspace().child(localName).child(local_test_headFile).exists());
    }
    
    //TODO: test excludedRegions
    //TODO: test update option
    //TODO: test cvs tagging
    //TODO: test changelog generation
    //TODO: test skip changelog generation
    //TODO: test prune option
    //TODO: test compression level (if possible)
}
