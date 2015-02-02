package com.thoughtworks.go.domain.cctray;

import com.thoughtworks.go.config.CaseInsensitiveString;
import com.thoughtworks.go.config.CruiseConfig;
import com.thoughtworks.go.config.PipelineConfig;
import com.thoughtworks.go.config.StageConfig;
import com.thoughtworks.go.domain.activity.ProjectStatus;
import com.thoughtworks.go.helper.GoConfigMother;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import java.util.Collections;
import java.util.Date;

import static java.util.Arrays.asList;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class CcTrayConfigChangeHandlerTest {
    @Mock
    private CcTrayCache cache;
    @Mock
    private CcTrayStageStatusLoader stageStatusLoader;
    @Captor
    ArgumentCaptor<ProjectStatus> statusCaptor;

    private CcTrayConfigChangeHandler handler;

    @Before
    public void setUp() throws Exception {
        initMocks(this);
        handler = new CcTrayConfigChangeHandler(cache, stageStatusLoader);
    }

    @Test
    public void shouldProvideCCTrayCacheWithAListOfAllProjectsInOrder() throws Exception {
        ProjectStatus pipeline1_stage1 = new ProjectStatus("pipeline1 :: stage", "Activity1", "Status1", "Label1", new Date(), "stage1-url");
        ProjectStatus pipeline1_stage1_job = new ProjectStatus("pipeline1 :: stage :: job", "Activity1-Job", "Status1-Job", "Label1-Job", new Date(), "job1-url");
        ProjectStatus pipeline2_stage1 = new ProjectStatus("pipeline2 :: stage", "Activity2", "Status2", "Label2", new Date(), "stage2-url");
        ProjectStatus pipeline2_stage1_job = new ProjectStatus("pipeline2 :: stage :: job", "Activity2-Job", "Status2-Job", "Label2-Job", new Date(), "job2-url");

        when(cache.get("pipeline1 :: stage")).thenReturn(pipeline1_stage1);
        when(cache.get("pipeline1 :: stage :: job")).thenReturn(pipeline1_stage1_job);
        when(cache.get("pipeline2 :: stage")).thenReturn(pipeline2_stage1);
        when(cache.get("pipeline2 :: stage :: job")).thenReturn(pipeline2_stage1_job);

        handler.call(GoConfigMother.configWithPipelines("pipeline2", "pipeline1")); /* Adds pipeline1 first in config. Then pipeline2. */

        verify(cache).replaceAllEntriesInCacheWith(eq(asList(pipeline1_stage1, pipeline1_stage1_job, pipeline2_stage1, pipeline2_stage1_job)));
        verifyZeroInteractions(stageStatusLoader);
    }

    @Test
    public void shouldPopulateNewCacheWithProjectsFromOldCacheWhenTheyExist() throws Exception {
        String stageProjectName = "pipeline1 :: stage";
        String jobProjectName = "pipeline1 :: stage :: job";

        ProjectStatus existingStageStatus = new ProjectStatus(stageProjectName, "OldActivity", "OldStatus", "OldLabel", new Date(), "stage-url");
        when(cache.get(stageProjectName)).thenReturn(existingStageStatus);

        ProjectStatus existingJobStatus = new ProjectStatus(jobProjectName, "OldActivity-Job", "OldStatus-Job", "OldLabel-Job", new Date(), "job-url");
        when(cache.get(jobProjectName)).thenReturn(existingJobStatus);


        handler.call(GoConfigMother.configWithPipelines("pipeline1"));


        verify(cache).replaceAllEntriesInCacheWith(eq(asList(existingStageStatus, existingJobStatus)));
        verifyZeroInteractions(stageStatusLoader);
    }

    @Test
    public void shouldPopulateNewCacheWithStageAndJobFromDB_WhenAStageIsNotFoundInTheOldCache() throws Exception {
        CruiseConfig config = GoConfigMother.configWithPipelines("pipeline1");

        String stageProjectName = "pipeline1 :: stage";
        String jobProjectName = "pipeline1 :: stage :: job";

        ProjectStatus statusOfStageInDB = new ProjectStatus(stageProjectName, "OldActivity", "OldStatus", "OldLabel", new Date(), "stage-url");
        ProjectStatus statusOfJobInDB = new ProjectStatus(jobProjectName, "OldActivity-Job", "OldStatus-Job", "OldLabel-Job", new Date(), "job-url");
        when(cache.get(stageProjectName)).thenReturn(null);
        when(stageStatusLoader.getStatusesForStageAndJobsOf(pipelineConfigFor(config, "pipeline1"), stageConfigFor(config, "pipeline1", "stage")))
                .thenReturn(asList(statusOfStageInDB, statusOfJobInDB));

        handler.call(config);


        verify(cache).replaceAllEntriesInCacheWith(eq(asList(statusOfStageInDB, statusOfJobInDB)));
    }

    @Test
    public void shouldHandleNewStagesInConfig_ByReplacingStagesMissingInDBWithNullStagesAndJobs() throws Exception {
        GoConfigMother goConfigMother = new GoConfigMother();
        CruiseConfig config = new CruiseConfig();
        goConfigMother.addPipeline(config, "pipeline1", "stage1", "job1");
        goConfigMother.addStageToPipeline(config, "pipeline1", "stage2", "job2");

        String stage1ProjectName = "pipeline1 :: stage1";
        String job1ProjectName = "pipeline1 :: stage1 :: job1";
        String stage2ProjectName = "pipeline1 :: stage2";
        String job2ProjectName = "pipeline1 :: stage2 :: job2";

        ProjectStatus statusOfStage1InCache = new ProjectStatus(stage1ProjectName, "OldActivity", "OldStatus", "OldLabel", new Date(), "stage-url");
        ProjectStatus statusOfJob1InCache = new ProjectStatus(job1ProjectName, "OldActivity-Job", "OldStatus-Job", "OldLabel-Job", new Date(), "job1-url");
        when(cache.get(stage1ProjectName)).thenReturn(statusOfStage1InCache);
        when(cache.get(job1ProjectName)).thenReturn(statusOfJob1InCache);

        when(cache.get(stage2ProjectName)).thenReturn(null);
        when(stageStatusLoader.getStatusesForStageAndJobsOf(pipelineConfigFor(config, "pipeline1"), stageConfigFor(config, "pipeline1", "stage2")))
                .thenReturn(Collections.<ProjectStatus>emptyList());


        handler.call(config);


        ProjectStatus expectedNullStatusForStage2 = new ProjectStatus.NullProjectStatus(stage2ProjectName);
        ProjectStatus expectedNullStatusForJob2 = new ProjectStatus.NullProjectStatus(job2ProjectName);
        verify(cache).replaceAllEntriesInCacheWith(eq(asList(statusOfStage1InCache, statusOfJob1InCache, expectedNullStatusForStage2, expectedNullStatusForJob2)));
    }

    /* Simulate adding a job, when server is down. DB does not know anything about that job. */
    @Test
    public void shouldHandleNewJobsInConfig_ByReplacingJobsMissingInDBWithNullJob() throws Exception {
        GoConfigMother goConfigMother = new GoConfigMother();
        CruiseConfig config = new CruiseConfig();
        goConfigMother.addPipeline(config, "pipeline1", "stage1", "job1", "NEW_JOB_IN_CONFIG");

        String stage1ProjectName = "pipeline1 :: stage1";
        String job1ProjectName = "pipeline1 :: stage1 :: job1";
        String projectNameOfNewJob = "pipeline1 :: stage1 :: NEW_JOB_IN_CONFIG";

        ProjectStatus statusOfStage1InDB = new ProjectStatus(stage1ProjectName, "OldActivity", "OldStatus", "OldLabel", new Date(), "stage-url");
        ProjectStatus statusOfJob1InDB = new ProjectStatus(job1ProjectName, "OldActivity-Job", "OldStatus-Job", "OldLabel-Job", new Date(), "job1-url");
        when(cache.get(stage1ProjectName)).thenReturn(null);
        when(stageStatusLoader.getStatusesForStageAndJobsOf(pipelineConfigFor(config, "pipeline1"), stageConfigFor(config, "pipeline1", "stage1")))
                .thenReturn(asList(statusOfStage1InDB, statusOfJob1InDB));


        handler.call(config);


        ProjectStatus expectedNullStatusForNewJob = new ProjectStatus.NullProjectStatus(projectNameOfNewJob);
        verify(cache).replaceAllEntriesInCacheWith(eq(asList(statusOfStage1InDB, statusOfJob1InDB, expectedNullStatusForNewJob)));
    }

    /* Simulate adding a job, in a running system. Cache has the stage info, but not the job info. */
    @Test
    public void shouldHandleNewJobsInConfig_ByReplacingJobsMissingInConfigWithNullJob() throws Exception {
        String stage1ProjectName = "pipeline1 :: stage1";
        String job1ProjectName = "pipeline1 :: stage1 :: job1";
        String projectNameOfNewJob = "pipeline1 :: stage1 :: NEW_JOB_IN_CONFIG";

        ProjectStatus statusOfStage1InCache = new ProjectStatus(stage1ProjectName, "OldActivity", "OldStatus", "OldLabel", new Date(), "stage-url");
        ProjectStatus statusOfJob1InCache = new ProjectStatus(job1ProjectName, "OldActivity-Job", "OldStatus-Job", "OldLabel-Job", new Date(), "job1-url");
        when(cache.get(stage1ProjectName)).thenReturn(statusOfStage1InCache);
        when(cache.get(job1ProjectName)).thenReturn(statusOfJob1InCache);
        when(cache.get(projectNameOfNewJob)).thenReturn(null);

        GoConfigMother goConfigMother = new GoConfigMother();
        CruiseConfig config = new CruiseConfig();
        goConfigMother.addPipeline(config, "pipeline1", "stage1", "job1", "NEW_JOB_IN_CONFIG");


        handler.call(config);


        ProjectStatus expectedNullStatusForNewJob = new ProjectStatus.NullProjectStatus(projectNameOfNewJob);
        verify(cache).replaceAllEntriesInCacheWith(eq(asList(statusOfStage1InCache, statusOfJob1InCache, expectedNullStatusForNewJob)));
        verifyZeroInteractions(stageStatusLoader);
    }

    @Test
    public void shouldRemoveExtraJobsFromCache_WhichAreNoLongerInConfig() throws Exception {
        String stage1ProjectName = "pipeline1 :: stage1";
        String job1ProjectName = "pipeline1 :: stage1 :: job1";
        String projectNameOfJobWhichWillBeRemoved = "pipeline1 :: stage1 :: JOB_IN_OLD_CONFIG";

        ProjectStatus statusOfStage1InCache = new ProjectStatus(stage1ProjectName, "OldActivity", "OldStatus", "OldLabel", new Date(), "stage-url");
        ProjectStatus statusOfJob1InCache = new ProjectStatus(job1ProjectName, "OldActivity-Job", "OldStatus-Job", "OldLabel-Job", new Date(), "job1-url");
        ProjectStatus statusOfOldJobInCache = new ProjectStatus(projectNameOfJobWhichWillBeRemoved, "OldActivity-Job", "OldStatus-Job", "OldLabel-Job", new Date(), "job2-url");
        when(cache.get(stage1ProjectName)).thenReturn(statusOfStage1InCache);
        when(cache.get(job1ProjectName)).thenReturn(statusOfJob1InCache);
        when(cache.get(projectNameOfJobWhichWillBeRemoved)).thenReturn(statusOfOldJobInCache);

        GoConfigMother goConfigMother = new GoConfigMother();
        CruiseConfig config = new CruiseConfig();
        goConfigMother.addPipeline(config, "pipeline1", "stage1", "job1");


        handler.call(config);


        verify(cache).replaceAllEntriesInCacheWith(eq(asList(statusOfStage1InCache, statusOfJob1InCache)));
        verifyZeroInteractions(stageStatusLoader);
    }

    private PipelineConfig pipelineConfigFor(CruiseConfig config, String pipelineName) {
        return config.pipelineConfigByName(new CaseInsensitiveString(pipelineName));
    }

    private StageConfig stageConfigFor(CruiseConfig config, String pipelineName, String stageName) {
        return pipelineConfigFor(config, pipelineName).getStage(new CaseInsensitiveString(stageName));
    }
}