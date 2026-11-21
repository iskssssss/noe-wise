package com.skillforge.core.foundation.jobs;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.skillforge.core.foundation.assets.FoundationFileService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FoundationJobSchedulerTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void retentionModeKeepsMaterialsAndStillExecutesHostJobs(boolean platform) {
        var jobs = mock(FoundationJobService.class);
        var files = mock(FoundationFileService.class);
        var scheduler = new FoundationJobScheduler(jobs, files, platform, false);

        scheduler.cleanup();
        scheduler.poll();

        verify(jobs, never()).cleanup(platform);
        verify(files, never()).cleanup();
        verify(jobs).runNext(platform);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void enabledCleanupUsesTheHostQueueAndSharedFileLifecycle(boolean platform) {
        var jobs = mock(FoundationJobService.class);
        var files = mock(FoundationFileService.class);
        var scheduler = new FoundationJobScheduler(jobs, files, platform, true);

        scheduler.cleanup();

        verify(jobs).cleanup(platform);
        verify(files).cleanup();
    }
}
