/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kafka.streams.processor.internals;

import org.apache.kafka.clients.consumer.CommitFailedException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.ProducerFencedException;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.common.utils.Utils;
import org.apache.kafka.streams.processor.TaskId;
import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;

import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AssignedTasksTest {

    private final AssignedTasks<AbstractTask> assignedTasks = new AssignedTasks<>("log", "task", Time.SYSTEM);
    private final AbstractTask t1 = EasyMock.createMock(AbstractTask.class);
    private final AbstractTask t2 = EasyMock.createMock(AbstractTask.class);
    private final TopicPartition tp1 = new TopicPartition("t1", 0);
    private final TopicPartition tp2 = new TopicPartition("t2", 0);
    private final TopicPartition changeLog1 = new TopicPartition("cl1", 0);
    private final TopicPartition changeLog2 = new TopicPartition("cl2", 0);
    private final TaskId taskId1 = new TaskId(0, 0);
    private final TaskId taskId2 = new TaskId(1, 0);
    private final Metrics metrics = new Metrics();
    private final Sensor punctuateSensor = metrics.sensor("punctuate");
    private final Sensor commitSensor = metrics.sensor("commit");

    @Before
    public void before() {
        EasyMock.expect(t1.id()).andReturn(taskId1).anyTimes();
        EasyMock.expect(t2.id()).andReturn(taskId2).anyTimes();
    }

    @Test
    public void shouldGetPartitionsFromNewTasksThatHaveStateStores() {
        EasyMock.expect(t1.hasStateStores()).andReturn(true);
        EasyMock.expect(t2.hasStateStores()).andReturn(true);
        EasyMock.expect(t1.partitions()).andReturn(Collections.singleton(tp1)).anyTimes();
        EasyMock.expect(t2.partitions()).andReturn(Collections.singleton(tp2)).anyTimes();
        EasyMock.replay(t1, t2);

        assignedTasks.addNewTask(t1);
        assignedTasks.addNewTask(t2);

        final Set<TopicPartition> partitions = assignedTasks.uninitializedPartitions();
        assertThat(partitions, equalTo(Utils.mkSet(tp1, tp2)));
        EasyMock.verify(t1, t2);
    }

    @Test
    public void shouldNotGetPartitionsFromNewTasksWithoutStateStores() {
        EasyMock.expect(t1.hasStateStores()).andReturn(false);
        EasyMock.expect(t2.hasStateStores()).andReturn(false);
        EasyMock.expect(t1.partitions()).andReturn(Collections.singleton(tp1)).anyTimes();
        EasyMock.expect(t2.partitions()).andReturn(Collections.singleton(tp2)).anyTimes();
        EasyMock.replay(t1, t2);

        assignedTasks.addNewTask(t1);
        assignedTasks.addNewTask(t2);

        final Set<TopicPartition> partitions = assignedTasks.uninitializedPartitions();
        assertTrue(partitions.isEmpty());
        EasyMock.verify(t1, t2);
    }

    @Test
    public void shouldInitializeNewTasks() {
        EasyMock.expect(t1.initialize()).andReturn(false);
        EasyMock.expect(t1.partitions()).andReturn(Collections.singleton(tp1)).anyTimes();
        EasyMock.replay(t1);

        assignedTasks.addNewTask(t1);
        assignedTasks.initializeNewTasks();

        EasyMock.verify(t1);
    }

    @Test
    public void shouldMoveInitializedTasksNeedingRestoreToRestoring() {
        EasyMock.expect(t1.initialize()).andReturn(false);
        EasyMock.expect(t2.initialize()).andReturn(true);
        EasyMock.expect(t1.partitions()).andReturn(Collections.singleton(tp1)).anyTimes();
        EasyMock.expect(t2.partitions()).andReturn(Collections.singleton(tp2)).anyTimes();
        EasyMock.expect(t2.changelogPartitions()).andReturn(Collections.<TopicPartition>emptyList());

        EasyMock.replay(t1, t2);

        assignedTasks.addNewTask(t1);
        assignedTasks.addNewTask(t2);

        assignedTasks.initializeNewTasks();

        Collection<AbstractTask> restoring = assignedTasks.restoringTasks();
        assertThat(restoring.size(), equalTo(1));
        assertSame(restoring.iterator().next(), t1);
    }

    @Test
    public void shouldMoveInitializedTasksThatDontNeedRestoringToRunning() {
        EasyMock.expect(t2.initialize()).andReturn(true);
        EasyMock.expect(t2.partitions()).andReturn(Collections.singleton(tp2)).anyTimes();
        EasyMock.expect(t2.changelogPartitions()).andReturn(Collections.<TopicPartition>emptyList());

        EasyMock.replay(t2);

        assignedTasks.addNewTask(t2);
        assignedTasks.initializeNewTasks();

        assertThat(assignedTasks.runningTaskIds(), equalTo(Collections.singleton(taskId2)));
    }

    @Test
    public void shouldTransitionFullyRestoredTasksToRunning() {
        final Set<TopicPartition> task1Partitions = Utils.mkSet(tp1);
        EasyMock.expect(t1.initialize()).andReturn(false);
        EasyMock.expect(t1.partitions()).andReturn(task1Partitions).anyTimes();
        EasyMock.expect(t1.changelogPartitions()).andReturn(Utils.mkSet(changeLog1, changeLog2)).anyTimes();
        EasyMock.replay(t1);

        assignedTasks.addNewTask(t1);

        assignedTasks.initializeNewTasks();

        assertTrue(assignedTasks.updateRestored(Utils.mkSet(changeLog1)).isEmpty());
        Set<TopicPartition> partitions = assignedTasks.updateRestored(Utils.mkSet(changeLog2));
        assertThat(partitions, equalTo(task1Partitions));
        assertThat(assignedTasks.runningTaskIds(), equalTo(Collections.singleton(taskId1)));
    }

    @Test
    public void shouldSuspendRunningTasks() {
        mockRunningTaskSuspension();
        EasyMock.replay(t1);

        suspendTask();

        assertThat(assignedTasks.previousTaskIds(), equalTo(Collections.singleton(taskId1)));
        EasyMock.verify(t1);
    }

    @Test
    public void shouldCloseUnInitializedTasksOnSuspend() {
        t1.close(false, false);
        EasyMock.expectLastCall();
        EasyMock.expect(t1.partitions()).andReturn(Collections.singleton(tp1)).anyTimes();
        EasyMock.replay(t1);

        assignedTasks.addNewTask(t1);
        assignedTasks.suspend();

        EasyMock.verify(t1);
    }

    @Test
    public void shouldNotSuspendSuspendedTasks() {
        mockRunningTaskSuspension();
        EasyMock.replay(t1);

        suspendTask();
        assignedTasks.suspend();
        EasyMock.verify(t1);
    }

    @Test
    public void shouldCloseTaskOnSuspendWhenRuntimeException() {
        mockInitializedTask();
        t1.suspend();
        EasyMock.expectLastCall().andThrow(new RuntimeException("KABOOM!"));
        t1.close(false, false);
        EasyMock.expectLastCall();
        EasyMock.replay(t1);

        final RuntimeException expectedException = suspendTask();
        assertThat(expectedException, not(nullValue()));
        EasyMock.verify(t1);
    }

    @Test
    public void shouldCloseTaskOnSuspendWhenProducerFencedException() {
        mockInitializedTask();
        t1.suspend();
        EasyMock.expectLastCall().andThrow(new ProducerFencedException("KABOOM!"));
        t1.close(false, true);
        EasyMock.expectLastCall();
        EasyMock.replay(t1);

        assertThat(suspendTask(), nullValue());
        assertTrue(assignedTasks.previousTaskIds().isEmpty());
        EasyMock.verify(t1);
    }

    private void mockInitializedTask() {
        EasyMock.expect(t1.initialize()).andReturn(true);
        EasyMock.expect(t1.partitions()).andReturn(Collections.singleton(tp1)).anyTimes();
        EasyMock.expect(t1.changelogPartitions()).andReturn(Collections.<TopicPartition>emptyList());
    }

    @Test
    public void shouldResumeMatchingSuspendedTasks() {
        mockRunningTaskSuspension();
        t1.resume();
        EasyMock.expectLastCall();
        EasyMock.replay(t1);

        suspendTask();

        assertTrue(assignedTasks.maybeResumeSuspendedTask(taskId1, Collections.singleton(tp1)));
        assertThat(assignedTasks.runningTaskIds(), equalTo(Collections.singleton(taskId1)));
        EasyMock.verify(t1);
    }

    @Test
    public void shouldCommitRunningTasks() {
        mockInitializedTask();
        t1.commit();
        EasyMock.expectLastCall();
        EasyMock.replay(t1);

        assignedTasks.addNewTask(t1);
        assignedTasks.initializeNewTasks();

        assignedTasks.commit();
        EasyMock.verify(t1);
    }

    @Test
    public void shouldCloseTaskOnCommitIfProduceFencedException() {
        mockInitializedTask();
        t1.commit();
        EasyMock.expectLastCall().andThrow(new ProducerFencedException(""));
        t1.close(false, true);
        EasyMock.expectLastCall();
        EasyMock.replay(t1);
        assignedTasks.addNewTask(t1);
        assignedTasks.initializeNewTasks();

        assignedTasks.commit();
        assertTrue(assignedTasks.runningTasks().isEmpty());
        EasyMock.verify(t1);
    }

    @Test
    public void shouldNotThrowCommitFailedExceptionOnCommit() {
        mockInitializedTask();
        t1.commit();
        EasyMock.expectLastCall().andThrow(new CommitFailedException());
        EasyMock.replay(t1);
        assignedTasks.addNewTask(t1);
        assignedTasks.initializeNewTasks();

        assignedTasks.commit();
        assertThat(assignedTasks.runningTaskIds(), equalTo(Collections.singleton(taskId1)));
        EasyMock.verify(t1);
    }

    @Test
    public void shouldThrowExceptionOnCommitWhenNotCommitFailedOrProducerFenced() {
        mockInitializedTask();
        t1.commit();
        EasyMock.expectLastCall().andThrow(new RuntimeException(""));
        EasyMock.replay(t1);
        assignedTasks.addNewTask(t1);
        assignedTasks.initializeNewTasks();

        try {
            assignedTasks.commit();
            fail("Should have thrown exception");
        } catch (Exception e) {
            // ok
        }
        assertThat(assignedTasks.runningTaskIds(), equalTo(Collections.singleton(taskId1)));
        EasyMock.verify(t1);
    }

    @Test
    public void shouldProcessRunningTasks() {
        mockInitializedTask();
        EasyMock.expect(t1.process()).andReturn(true);
        EasyMock.replay(t1);

        assignedTasks.addNewTask(t1);
        assignedTasks.initializeNewTasks();

        assertThat(assignedTasks.process(), equalTo(1));
        EasyMock.verify(t1);
    }

    @Test
    public void shouldCloseTaskOnProcessIfProducerFencedException() {
        mockInitializedTask();
        EasyMock.expect(t1.process()).andThrow(new ProducerFencedException(""));
        t1.close(false, true);
        EasyMock.expectLastCall();
        EasyMock.replay(t1);

        assignedTasks.addNewTask(t1);
        assignedTasks.initializeNewTasks();

        assignedTasks.process();
        assertTrue(assignedTasks.runningTasks().isEmpty());
        EasyMock.verify(t1);
    }

    @Test
    public void shouldThrowExceptionOnProcessWhenNotCommitFailedOrProducerFencedException() {
        mockInitializedTask();
        EasyMock.expect(t1.process()).andThrow(new RuntimeException(""));
        EasyMock.replay(t1);

        assignedTasks.addNewTask(t1);
        assignedTasks.initializeNewTasks();

        try {
            assignedTasks.process();
            fail("should have thrown exception");
        } catch (Exception e) {
            // okd
        }
        assertThat(assignedTasks.runningTaskIds(), equalTo(Collections.singleton(taskId1)));
        EasyMock.verify(t1);
    }

    @Test
    public void shouldPunctuateRunningTasks() {
        mockInitializedTask();
        EasyMock.expect(t1.maybePunctuate()).andReturn(true);
        EasyMock.expect(t1.commitNeeded()).andReturn(false);
        EasyMock.replay(t1);

        assignedTasks.addNewTask(t1);
        assignedTasks.initializeNewTasks();

        assignedTasks.punctuateAndCommit(commitSensor, punctuateSensor);
        EasyMock.verify(t1);
    }

    @Test
    public void shouldCommitRunningTasksIfNeeded() {
        mockInitializedTask();
        EasyMock.expect(t1.maybePunctuate()).andReturn(true);
        EasyMock.expect(t1.commitNeeded()).andReturn(true);
        t1.commit();
        EasyMock.expectLastCall();
        EasyMock.replay(t1);

        assignedTasks.addNewTask(t1);
        assignedTasks.initializeNewTasks();

        assignedTasks.punctuateAndCommit(commitSensor, punctuateSensor);
        EasyMock.verify(t1);
    }

    @Test
    public void shouldThrowExceptionOnPunctuateAndCommitWhenNotCommitFailedOrProducerFencedException() {
        mockInitializedTask();
        EasyMock.expect(t1.maybePunctuate()).andThrow(new RuntimeException(""));
        EasyMock.replay(t1);
        assignedTasks.addNewTask(t1);
        assignedTasks.initializeNewTasks();

        try {
            assignedTasks.punctuateAndCommit(commitSensor, punctuateSensor);
            fail("should have thrown exception");
        } catch (Exception e) {
            // ok
        }
        assertThat(assignedTasks.runningTaskIds(), equalTo(Collections.singleton(taskId1)));
        EasyMock.verify(t1);
    }

    @Test
    public void shouldCloseTaskOnPunctuateAndCommitIfProducerFencedException() {
        mockInitializedTask();
        EasyMock.expect(t1.maybePunctuate()).andThrow(new ProducerFencedException(""));
        t1.close(false, true);
        EasyMock.expectLastCall();
        EasyMock.replay(t1);
        assignedTasks.addNewTask(t1);
        assignedTasks.initializeNewTasks();

        assignedTasks.punctuateAndCommit(commitSensor, punctuateSensor);
        assertTrue(assignedTasks.runningTasks().isEmpty());
        EasyMock.verify(t1);

    }

    private RuntimeException suspendTask() {
        assignedTasks.addNewTask(t1);
        assignedTasks.initializeNewTasks();
        return assignedTasks.suspend();
    }

    private void mockRunningTaskSuspension() {
        EasyMock.expect(t1.initialize()).andReturn(true);
        EasyMock.expect(t1.partitions()).andReturn(Collections.singleton(tp1)).anyTimes();
        EasyMock.expect(t1.changelogPartitions()).andReturn(Collections.<TopicPartition>emptyList()).anyTimes();
        t1.suspend();
        EasyMock.expectLastCall();
    }

}