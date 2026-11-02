package com.skillforge.core.agent.capabilitymount;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skillforge.core.capability.CapabilityMount;
import com.skillforge.core.capability.CapabilityMount.SkillMountConfig;
import com.skillforge.core.capability.CapabilityRef;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.capability.SkillActivationMode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CapabilityMountPersistenceServiceTest {

    @Mock
    private CapabilityMountPersistenceRepository repository;

    @Test
    void firstCreateCreatesGenerationOneWithoutAnExtraIncrement() {
        CapabilityMountPersistenceService service = new CapabilityMountPersistenceService(repository);
        when(repository.ensureAndLock(10L, 20L)).thenReturn(lock(1L, true));
        when(repository.readState(10L, 20L)).thenReturn(result(1L));

        CapabilityMountSetResult result = service.create(createCommand());

        assertEquals(1L, result.generation());
        verify(repository).insert(argThat(mount -> mount.revision() == 1L
                && mount.active() && mount.capability().type() == CapabilityType.SKILL));
        verify(repository, never()).incrementGeneration(any(), any(), anyLong());
    }

    @Test
    void existingSetIncrementsGenerationExactlyOnceAfterSuccessfulCreate() {
        CapabilityMountPersistenceService service = new CapabilityMountPersistenceService(repository);
        when(repository.ensureAndLock(10L, 20L)).thenReturn(lock(6L, false));
        when(repository.readState(10L, 20L)).thenReturn(result(7L));

        service.create(createCommand());

        verify(repository).insert(any(CapabilityMount.class));
        verify(repository).incrementGeneration(10L, 20L, 6L);
    }

    @Test
    void updateRequiresExpectedRevisionAndAdvancesMountAndGeneration() {
        CapabilityMountPersistenceService service = new CapabilityMountPersistenceService(repository);
        CapabilityMount current = mount("101", 3L);
        when(repository.lockExisting(10L, 20L)).thenReturn(set(8L));
        when(repository.findForUpdate(10L, 20L, "101")).thenReturn(current);
        when(repository.readState(10L, 20L)).thenReturn(result(9L));

        service.update(new UpdateCapabilityMountCommand(
                10L, 20L, "101", 3L, current.capability(), current.config(), 4));

        verify(repository).update(argThat(updated -> updated.revision() == 4L
                && updated.priority() == 4), eq(3L));
        verify(repository).incrementGeneration(10L, 20L, 8L);
    }

    @Test
    void staleCasDoesNotWriteOrAdvanceGeneration() {
        CapabilityMountPersistenceService service = new CapabilityMountPersistenceService(repository);
        CapabilityMount current = mount("101", 3L);
        when(repository.lockExisting(10L, 20L)).thenReturn(set(8L));
        when(repository.findForUpdate(10L, 20L, "101")).thenReturn(current);

        assertThrows(CapabilityMountRevisionConflictException.class, () -> service.update(
                new UpdateCapabilityMountCommand(
                        10L, 20L, "101", 2L, current.capability(), current.config(), 4)));

        verify(repository, never()).update(any(CapabilityMount.class), anyLong());
        verify(repository, never()).incrementGeneration(any(), any(), anyLong());
    }

    @Test
    void updateCannotReplaceStableCapabilityIdentityOrAdvanceGeneration() {
        CapabilityMountPersistenceService service = new CapabilityMountPersistenceService(repository);
        CapabilityMount current = mount("101", 3L);
        when(repository.lockExisting(10L, 20L)).thenReturn(set(8L));
        when(repository.findForUpdate(10L, 20L, "101")).thenReturn(current);

        CapabilityRef anotherDefinition = new CapabilityRef(
                CapabilityType.SKILL, "9999", "10000");
        assertThrows(IllegalArgumentException.class, () -> service.update(
                new UpdateCapabilityMountCommand(
                        10L, 20L, "101", 3L, anotherDefinition,
                        current.config(), 4)));

        verify(repository, never()).update(any(CapabilityMount.class), anyLong());
        verify(repository, never()).incrementGeneration(any(), any(), anyLong());
    }

    @Test
    void failedCreateDoesNotAdvanceGeneration() {
        CapabilityMountPersistenceService service = new CapabilityMountPersistenceService(repository);
        when(repository.ensureAndLock(10L, 20L)).thenReturn(lock(6L, false));
        org.mockito.Mockito.doThrow(new CapabilityMountPersistenceException("write failed"))
                .when(repository).insert(any(CapabilityMount.class));

        assertThrows(CapabilityMountPersistenceException.class,
                () -> service.create(createCommand()));

        verify(repository, never()).incrementGeneration(any(), any(), anyLong());
    }

    private CreateCapabilityMountCommand createCommand() {
        return new CreateCapabilityMountCommand(
                10L, 20L,
                new CapabilityRef(CapabilityType.SKILL, "1001", "1002"),
                new SkillMountConfig(SkillActivationMode.ALWAYS, 1_000L),
                0);
    }

    private CapabilityMount mount(String id, long revision) {
        return new CapabilityMount(
                id, 10L, 20L,
                new CapabilityRef(CapabilityType.SKILL, "1001", "1002"),
                new SkillMountConfig(SkillActivationMode.ALWAYS, 1_000L),
                0, revision, true);
    }

    private CapabilityMountPersistenceRepository.MountSetLock lock(
            long generation, boolean created) {
        return new CapabilityMountPersistenceRepository.MountSetLock(set(generation), created);
    }

    private AgentCapabilityMountSetDO set(long generation) {
        AgentCapabilityMountSetDO value = new AgentCapabilityMountSetDO();
        value.setTenantId(10L);
        value.setAgentId(20L);
        value.setGeneration(generation);
        return value;
    }

    private CapabilityMountSetResult result(long generation) {
        return new CapabilityMountSetResult(10L, 20L, generation, List.of());
    }
}
