/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.rpc.akka;

import akka.actor.ActorSystem;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.runtime.akka.AkkaUtils;
import org.apache.flink.runtime.rpc.RpcEndpoint;
import org.apache.flink.runtime.rpc.RpcGateway;
import org.apache.flink.runtime.rpc.RpcService;
import org.apache.flink.runtime.rpc.akka.exceptions.AkkaRpcException;
import org.apache.flink.runtime.rpc.exceptions.RpcConnectionException;
import org.apache.flink.util.FlinkException;
import org.apache.flink.util.TestLogger;

import org.hamcrest.core.Is;
import org.junit.AfterClass;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AkkaRpcActorTest extends TestLogger {

	// ------------------------------------------------------------------------
	//  shared test members
	// ------------------------------------------------------------------------

	private static ActorSystem actorSystem = AkkaUtils.createDefaultActorSystem();

	private static Time timeout = Time.milliseconds(10000L);

	private static AkkaRpcService akkaRpcService =
		new AkkaRpcService(actorSystem, timeout);

	@AfterClass
	public static void shutdown() {
		akkaRpcService.stopService();
		actorSystem.shutdown();
		actorSystem.awaitTermination();
	}

	/**
	 * Tests that the rpc endpoint and the associated rpc gateway have the same addresses.
	 * @throws Exception
	 */
	@Test
	public void testAddressResolution() throws Exception {
		DummyRpcEndpoint rpcEndpoint = new DummyRpcEndpoint(akkaRpcService);

		CompletableFuture<DummyRpcGateway> futureRpcGateway = akkaRpcService.connect(rpcEndpoint.getAddress(), DummyRpcGateway.class);

		DummyRpcGateway rpcGateway = futureRpcGateway.get(timeout.getSize(), timeout.getUnit());

		assertEquals(rpcEndpoint.getAddress(), rpcGateway.getAddress());
	}

	/**
	 * Tests that a {@link RpcConnectionException} is thrown if the rpc endpoint cannot be connected to.
	 */
	@Test
	public void testFailingAddressResolution() throws Exception {
		CompletableFuture<DummyRpcGateway> futureRpcGateway = akkaRpcService.connect("foobar", DummyRpcGateway.class);

		try {
			futureRpcGateway.get(timeout.getSize(), timeout.getUnit());

			fail("The rpc connection resolution should have failed.");
		} catch (ExecutionException exception) {
			// we're expecting a RpcConnectionException
			assertTrue(exception.getCause() instanceof RpcConnectionException);
		}
	}

	/**
	 * Tests that the {@link AkkaRpcActor} discards messages until the corresponding
	 * {@link RpcEndpoint} has been started.
	 */
	@Test
	public void testMessageDiscarding() throws Exception {
		int expectedValue = 1337;

		DummyRpcEndpoint rpcEndpoint = new DummyRpcEndpoint(akkaRpcService);

		DummyRpcGateway rpcGateway = rpcEndpoint.getSelfGateway(DummyRpcGateway.class);

		// this message should be discarded and completed with an AkkaRpcException
		CompletableFuture<Integer> result = rpcGateway.foobar();

		try {
			result.get(timeout.getSize(), timeout.getUnit());
			fail("Expected an AkkaRpcException.");
		} catch (ExecutionException ee) {
			// expected this exception, because the endpoint has not been started
			assertTrue(ee.getCause() instanceof AkkaRpcException);
		}

		// set a new value which we expect to be returned
		rpcEndpoint.setFoobar(expectedValue);

		// start the endpoint so that it can process messages
		rpcEndpoint.start();

		// send the rpc again
		result = rpcGateway.foobar();

		// now we should receive a result :-)
		Integer actualValue = result.get(timeout.getSize(), timeout.getUnit());

		assertThat("The new foobar value should have been returned.", actualValue, Is.is(expectedValue));

		rpcEndpoint.shutDown();
	}

	/**
	 * Tests that we receive a RpcConnectionException when calling a rpc method (with return type)
	 * on a wrong rpc endpoint.
	 *
	 * @throws Exception
	 */
	@Test
	public void testWrongGatewayEndpointConnection() throws Exception {
		DummyRpcEndpoint rpcEndpoint = new DummyRpcEndpoint(akkaRpcService);

		rpcEndpoint.start();

		CompletableFuture<WrongRpcGateway> futureGateway = akkaRpcService.connect(rpcEndpoint.getAddress(), WrongRpcGateway.class);

		WrongRpcGateway gateway = futureGateway.get(timeout.getSize(), timeout.getUnit());

		// since it is a tell operation we won't receive a RpcConnectionException, it's only logged
		gateway.tell("foobar");

		CompletableFuture<Boolean> result = gateway.barfoo();

		try {
			result.get(timeout.getSize(), timeout.getUnit());
			fail("We expected a RpcConnectionException.");
		} catch (ExecutionException executionException) {
			assertTrue(executionException.getCause() instanceof RpcConnectionException);
		}
	}

	/**
	 * Tests that we can wait for a RpcEndpoint to terminate.
	 *
	 * @throws ExecutionException
	 * @throws InterruptedException
	 */
	@Test(timeout=5000)
	public void testRpcEndpointTerminationFuture() throws Exception {
		final DummyRpcEndpoint rpcEndpoint = new DummyRpcEndpoint(akkaRpcService);
		rpcEndpoint.start();

		CompletableFuture<Void> terminationFuture = rpcEndpoint.getTerminationFuture();

		assertFalse(terminationFuture.isDone());

		CompletableFuture.runAsync(
			() -> rpcEndpoint.shutDown(),
			actorSystem.dispatcher());

		// wait until the rpc endpoint has terminated
		terminationFuture.get();
	}

	@Test
	public void testExceptionPropagation() throws Exception {
		ExceptionalEndpoint rpcEndpoint = new ExceptionalEndpoint(akkaRpcService);
		rpcEndpoint.start();

		ExceptionalGateway rpcGateway = rpcEndpoint.getSelfGateway(ExceptionalGateway.class);
		CompletableFuture<Integer> result = rpcGateway.doStuff();

		try {
			result.get(timeout.getSize(), timeout.getUnit());
			fail("this should fail with an exception");
		}
		catch (ExecutionException e) {
			Throwable cause = e.getCause();
			assertEquals(RuntimeException.class, cause.getClass());
			assertEquals("my super specific test exception", cause.getMessage());
		}
	}

	@Test
	public void testExceptionPropagationFuturePiping() throws Exception {
		ExceptionalFutureEndpoint rpcEndpoint = new ExceptionalFutureEndpoint(akkaRpcService);
		rpcEndpoint.start();

		ExceptionalGateway rpcGateway = rpcEndpoint.getSelfGateway(ExceptionalGateway.class);
		CompletableFuture<Integer> result = rpcGateway.doStuff();

		try {
			result.get(timeout.getSize(), timeout.getUnit());
			fail("this should fail with an exception");
		}
		catch (ExecutionException e) {
			Throwable cause = e.getCause();
			assertEquals(Exception.class, cause.getClass());
			assertEquals("some test", cause.getMessage());
		}
	}

	/**
	 * Tests that exception thrown in the postStop method are returned by the termination
	 * future.
	 */
	@Test
	public void testPostStopExceptionPropagation() throws Exception {
		FailingPostStopEndpoint rpcEndpoint = new FailingPostStopEndpoint(akkaRpcService, "FailingPostStopEndpoint");
		rpcEndpoint.start();

		rpcEndpoint.shutDown();

		CompletableFuture<Void> terminationFuture = rpcEndpoint.getTerminationFuture();

		try {
			terminationFuture.get();
		} catch (ExecutionException e) {
			assertTrue(e.getCause() instanceof FailingPostStopEndpoint.PostStopException);
		}
	}

	/**
	 * Checks that the postStop callback is executed within the main thread.
	 */
	@Test
	public void testPostStopExecutedByMainThread() throws Exception {
		SimpleRpcEndpoint simpleRpcEndpoint = new SimpleRpcEndpoint(akkaRpcService, "SimpleRpcEndpoint");
		simpleRpcEndpoint.start();

		simpleRpcEndpoint.shutDown();

		CompletableFuture<Void> terminationFuture = simpleRpcEndpoint.getTerminationFuture();

		// check that we executed the postStop method in the main thread, otherwise an exception
		// would be thrown here.
		terminationFuture.get();
	}

	// ------------------------------------------------------------------------
	//  Test Actors and Interfaces
	// ------------------------------------------------------------------------

	private interface DummyRpcGateway extends RpcGateway {
		CompletableFuture<Integer> foobar();
	}

	private interface WrongRpcGateway extends RpcGateway {
		CompletableFuture<Boolean> barfoo();
		void tell(String message);
	}

	private static class DummyRpcEndpoint extends RpcEndpoint implements DummyRpcGateway {

		private volatile int _foobar = 42;

		protected DummyRpcEndpoint(RpcService rpcService) {
			super(rpcService);
		}

		@Override
		public CompletableFuture<Integer> foobar() {
			return CompletableFuture.completedFuture(_foobar);
		}

		public void setFoobar(int value) {
			_foobar = value;
		}
	}

	// ------------------------------------------------------------------------

	private interface ExceptionalGateway extends RpcGateway {
		CompletableFuture<Integer> doStuff();
	}

	private static class ExceptionalEndpoint extends RpcEndpoint implements ExceptionalGateway {

		protected ExceptionalEndpoint(RpcService rpcService) {
			super(rpcService);
		}

		@Override
		public CompletableFuture<Integer> doStuff() {
			throw new RuntimeException("my super specific test exception");
		}
	}

	private static class ExceptionalFutureEndpoint extends RpcEndpoint implements ExceptionalGateway {

		protected ExceptionalFutureEndpoint(RpcService rpcService) {
			super(rpcService);
		}

		@Override
		public CompletableFuture<Integer> doStuff() {
			final CompletableFuture<Integer> future = new CompletableFuture<>();

			// complete the future slightly in the, well, future...
			new Thread() {
				@Override
				public void run() {
					try {
						Thread.sleep(10);
					} catch (InterruptedException ignored) {}
					future.completeExceptionally(new Exception("some test"));
				}
			}.start();

			return future;
		}
	}

	// ------------------------------------------------------------------------

	private static class SimpleRpcEndpoint extends RpcEndpoint implements RpcGateway {

		protected SimpleRpcEndpoint(RpcService rpcService, String endpointId) {
			super(rpcService, endpointId);
		}

		@Override
		public void postStop() {
			validateRunsInMainThread();
		}
	}

	// ------------------------------------------------------------------------

	private static class FailingPostStopEndpoint extends RpcEndpoint implements RpcGateway {

		protected FailingPostStopEndpoint(RpcService rpcService, String endpointId) {
			super(rpcService, endpointId);
		}

		@Override
		public void postStop() throws Exception {
			throw new PostStopException("Test exception.");
		}

		private static class PostStopException extends FlinkException {

			private static final long serialVersionUID = 6701096588415871592L;

			public PostStopException(String message) {
				super(message);
			}
		}
	}
}
