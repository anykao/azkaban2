package azkaban.test.execapp;

import java.io.File;
import java.io.IOException;
import java.util.StringTokenizer;

import junit.framework.Assert;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import azkaban.execapp.JobRunner;
import azkaban.execapp.event.Event;
import azkaban.execapp.event.Event.Type;
import azkaban.executor.ExecutableFlow;
import azkaban.executor.ExecutableNode;
import azkaban.executor.ExecutableFlow.Status;
import azkaban.executor.ExecutorLoader;
import azkaban.jobExecutor.ProcessJob;
import azkaban.jobtype.JobTypeManager;
import azkaban.test.executor.JavaJob;
import azkaban.test.executor.SleepJavaJob;
import azkaban.utils.Props;

public class JobRunnerTest {
	private File workingDir;
	private JobTypeManager jobtypeManager;
	
	public JobRunnerTest() {

	}

	@Before
	public void setUp() throws Exception {
		System.out.println("Create temp dir");
		workingDir = new File("_AzkabanTestDir_" + System.currentTimeMillis());
		if (workingDir.exists()) {
			FileUtils.deleteDirectory(workingDir);
		}
		workingDir.mkdirs();
		jobtypeManager = new JobTypeManager(null, this.getClass().getClassLoader());
		jobtypeManager.registerJobType("java", JavaJob.class);
	}

	@After
	public void tearDown() throws IOException {
		System.out.println("Teardown temp dir");
		if (workingDir != null) {
			FileUtils.deleteDirectory(workingDir);
			workingDir = null;
		}
	}

	@Test
	public void testBasicRun() {
		MockExecutorLoader loader = new MockExecutorLoader();
		EventCollectorListener eventCollector = new EventCollectorListener();
		JobRunner runner = createJobRunner(1, "testJob", 1, false, loader, eventCollector);
		ExecutableNode node = runner.getNode();
		
		eventCollector.handleEvent(Event.create(null, Event.Type.JOB_STARTED));
		Assert.assertTrue(runner.getStatus() != Status.SUCCEEDED || runner.getStatus() != Status.FAILED);
		
		runner.run();
		eventCollector.handleEvent(Event.create(null, Event.Type.JOB_FINISHED));
		
		Assert.assertTrue(runner.getStatus() == node.getStatus());
		Assert.assertTrue("Node status is " + node.getStatus(), node.getStatus() == Status.SUCCEEDED);
		Assert.assertTrue(node.getStartTime() > 0 && node.getEndTime() > 0);
		Assert.assertTrue( node.getEndTime() - node.getStartTime() > 1000);
		
		File logFile = new File(runner.getLogFilePath());
		Props outputProps = runner.getOutputProps();
		Assert.assertTrue(outputProps != null);
		Assert.assertTrue(logFile.exists());
		
		Assert.assertTrue(loader.getNodeUpdateCount(node.getJobId()) == 3);
		
		Assert.assertTrue(eventCollector.checkOrdering());
		try {
			eventCollector.checkEventExists(new Type[] {Type.JOB_STARTED, Type.JOB_STATUS_CHANGED, Type.JOB_FINISHED});
		}
		catch (Exception e) {
			Assert.fail(e.getMessage());
		}
	}

	@Test
	public void testFailedRun() {
		MockExecutorLoader loader = new MockExecutorLoader();
		EventCollectorListener eventCollector = new EventCollectorListener();
		JobRunner runner = createJobRunner(1, "testJob", 1, true, loader, eventCollector);
		ExecutableNode node = runner.getNode();
		
		Assert.assertTrue(runner.getStatus() != Status.SUCCEEDED || runner.getStatus() != Status.FAILED);
		runner.run();
		
		Assert.assertTrue(runner.getStatus() == node.getStatus());
		Assert.assertTrue(node.getStatus() == Status.FAILED);
		Assert.assertTrue(node.getStartTime() > 0 && node.getEndTime() > 0);
		Assert.assertTrue(node.getEndTime() - node.getStartTime() > 1000);
		
		File logFile = new File(runner.getLogFilePath());
		Props outputProps = runner.getOutputProps();
		Assert.assertTrue(outputProps == null);
		Assert.assertTrue(logFile.exists());
		Assert.assertTrue(eventCollector.checkOrdering());
		
		Assert.assertTrue(loader.getNodeUpdateCount(node.getJobId()) == 3);
		
		try {
			eventCollector.checkEventExists(new Type[] {Type.JOB_STARTED, Type.JOB_STATUS_CHANGED, Type.JOB_FINISHED});
		}
		catch (Exception e) {
			Assert.fail(e.getMessage());
		}
	}
	
	@Test
	public void testDisabledRun() {
		MockExecutorLoader loader = new MockExecutorLoader();
		EventCollectorListener eventCollector = new EventCollectorListener();
		JobRunner runner = createJobRunner(1, "testJob", 1, false, loader, eventCollector);
		ExecutableNode node = runner.getNode();
		
		node.setStatus(Status.DISABLED);
		
		// Should be disabled.
		Assert.assertTrue(runner.getStatus() == Status.DISABLED);
		runner.run();
		
		Assert.assertTrue(runner.getStatus() == node.getStatus());
		Assert.assertTrue(node.getStatus() == Status.SKIPPED);
		Assert.assertTrue(node.getStartTime() > 0 && node.getEndTime() > 0);
		// Give it 10 ms to fail.
		Assert.assertTrue( node.getEndTime() - node.getStartTime() < 10);
		
		// Log file and output files should not exist.
		Props outputProps = runner.getOutputProps();
		Assert.assertTrue(outputProps == null);
		Assert.assertTrue(runner.getLogFilePath() == null);
		Assert.assertTrue(eventCollector.checkOrdering());
		
		Assert.assertTrue(loader.getNodeUpdateCount(node.getJobId()) == null);
		
		try {
			eventCollector.checkEventExists(new Type[] {Type.JOB_STARTED, Type.JOB_FINISHED});
		}
		catch (Exception e) {
			Assert.fail(e.getMessage());
		}
	}
	
	@Test
	public void testPreKilledRun() {
		MockExecutorLoader loader = new MockExecutorLoader();
		EventCollectorListener eventCollector = new EventCollectorListener();
		JobRunner runner = createJobRunner(1, "testJob", 1, false, loader, eventCollector);
		ExecutableNode node = runner.getNode();
		
		node.setStatus(Status.KILLED);
		
		// Should be killed.
		Assert.assertTrue(runner.getStatus() == Status.KILLED);
		runner.run();
		
		// Should just skip the run and not change
		Assert.assertTrue(runner.getStatus() == node.getStatus());
		Assert.assertTrue(node.getStatus() == Status.KILLED);
		Assert.assertTrue(node.getStartTime() > 0 && node.getEndTime() > 0);
		// Give it 10 ms to fail.
		Assert.assertTrue( node.getEndTime() - node.getStartTime() < 10);
		
		Assert.assertTrue(loader.getNodeUpdateCount(node.getJobId()) == null);
		
		// Log file and output files should not exist.
		Props outputProps = runner.getOutputProps();
		Assert.assertTrue(outputProps == null);
		Assert.assertTrue(runner.getLogFilePath() == null);
		
		try {
			eventCollector.checkEventExists(new Type[] {Type.JOB_STARTED, Type.JOB_FINISHED});
		}
		catch (Exception e) {
			Assert.fail(e.getMessage());
		}
	}
	
	@Test
	public void testCancelRun() {
		MockExecutorLoader loader = new MockExecutorLoader();
		EventCollectorListener eventCollector = new EventCollectorListener();
		JobRunner runner = createJobRunner(13, "testJob", 10, false, loader, eventCollector);
		ExecutableNode node = runner.getNode();

		Assert.assertTrue(runner.getStatus() != Status.SUCCEEDED || runner.getStatus() != Status.FAILED);

		Thread thread = new Thread(runner);
		thread.start();
		
		synchronized(this) {
			try {
				wait(2000);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			runner.cancel();
			try {
				wait(500);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		
		Assert.assertTrue(runner.getStatus() == node.getStatus());
		Assert.assertTrue("Status is " + node.getStatus(), node.getStatus() == Status.FAILED);
		Assert.assertTrue(node.getStartTime() > 0 && node.getEndTime() > 0);
		// Give it 10 ms to fail.
		Assert.assertTrue(node.getEndTime() - node.getStartTime() < 3000);
		Assert.assertTrue(loader.getNodeUpdateCount(node.getJobId()) == 3);
		
		// Log file and output files should not exist.
		File logFile = new File(runner.getLogFilePath());
		Props outputProps = runner.getOutputProps();
		Assert.assertTrue(outputProps == null);
		Assert.assertTrue(logFile.exists());
		Assert.assertTrue(eventCollector.checkOrdering());
		
		try {
			eventCollector.checkEventExists(new Type[] {Type.JOB_STARTED, Type.JOB_STATUS_CHANGED, Type.JOB_FINISHED});
		}
		catch (Exception e) {
			System.out.println(e.getMessage());
			
			Assert.fail(e.getMessage());
		}
	}
	
	private Props createProps( int sleepSec, boolean fail) {
		Props props = new Props();
		props.put("type", "java");

		props.put(JavaJob.JOB_CLASS, SleepJavaJob.class.getName());
		props.put("seconds", sleepSec);
		props.put(ProcessJob.WORKING_DIR, workingDir.getPath());
		props.put("fail", String.valueOf(fail));

		return props;
	}

	private JobRunner createJobRunner(int execId, String name, int time, boolean fail, ExecutorLoader loader, EventCollectorListener listener) {
		ExecutableFlow flow = new ExecutableFlow();
		flow.setExecutionId(execId);
		ExecutableNode node = new ExecutableNode();
		node.setJobId(name);
		node.setExecutableFlow(flow);
		
		Props props = createProps(time, fail);
		
		JobRunner runner = new JobRunner(node, props, workingDir, loader, jobtypeManager);

		runner.addListener(listener);
		return runner;
	}
	
	private static String getSourcePathFromClass(Class containedClass) {
		File file = new File(containedClass.getProtectionDomain().getCodeSource().getLocation().getPath());

		if (!file.isDirectory() && file.getName().endsWith(".class")) {
			String name = containedClass.getName();
			StringTokenizer tokenizer = new StringTokenizer(name, ".");
			while(tokenizer.hasMoreTokens()) {
				tokenizer.nextElement();
				file = file.getParentFile();
			}
			return file.getPath();  
		}
		else {
			return containedClass.getProtectionDomain().getCodeSource().getLocation().getPath();
		}
	}
}