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

package org.apache.flink.runtime.blob;

import org.apache.flink.configuration.BlobServerOptions;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.SecurityOptions;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;

/**
 * This class contains unit tests for the {@link BlobClient} with ssl enabled.
 */
public class BlobClientSslTest extends BlobClientTest {

	/** The instance of the SSL BLOB server used during the tests. */
	private static BlobServer BLOB_SSL_SERVER;

	/** Instance of a non-SSL BLOB server with SSL-enabled security options. */
	private static BlobServer BLOB_NON_SSL_SERVER;

	/** The SSL blob service client configuration. */
	private static Configuration sslClientConfig;

	/** The non-SSL blob service client configuration with SSL-enabled security options. */
	private static Configuration nonSslClientConfig;

	@ClassRule
	public static TemporaryFolder temporarySslFolder = new TemporaryFolder();

	/**
	 * Starts the SSL enabled BLOB server.
	 */
	@BeforeClass
	public static void startSSLServer() throws IOException {
		Configuration config = new Configuration();
		config.setString(BlobServerOptions.STORAGE_DIRECTORY,
			temporarySslFolder.newFolder().getAbsolutePath());
		config.setBoolean(SecurityOptions.SSL_ENABLED, true);
		config.setString(SecurityOptions.SSL_KEYSTORE, "src/test/resources/local127.keystore");
		config.setString(SecurityOptions.SSL_KEYSTORE_PASSWORD, "password");
		config.setString(SecurityOptions.SSL_KEY_PASSWORD, "password");
		BLOB_SSL_SERVER = new BlobServer(config, new VoidBlobStore());

		sslClientConfig = new Configuration();
		sslClientConfig.setBoolean(SecurityOptions.SSL_ENABLED, true);
		sslClientConfig.setString(SecurityOptions.SSL_TRUSTSTORE, "src/test/resources/local127.truststore");
		sslClientConfig.setString(SecurityOptions.SSL_TRUSTSTORE_PASSWORD, "password");
	}

	@BeforeClass
	public static void startNonSSLServer() throws IOException {
		Configuration config = new Configuration();
		config.setString(BlobServerOptions.STORAGE_DIRECTORY,
			temporarySslFolder.newFolder().getAbsolutePath());
		config.setBoolean(SecurityOptions.SSL_ENABLED, true);
		config.setBoolean(BlobServerOptions.SSL_ENABLED, false);
		config.setString(SecurityOptions.SSL_KEYSTORE, "src/test/resources/local127.keystore");
		config.setString(SecurityOptions.SSL_KEYSTORE_PASSWORD, "password");
		config.setString(SecurityOptions.SSL_KEY_PASSWORD, "password");
		BLOB_NON_SSL_SERVER = new BlobServer(config, new VoidBlobStore());

		nonSslClientConfig = new Configuration();
		nonSslClientConfig.setBoolean(SecurityOptions.SSL_ENABLED, true);
		nonSslClientConfig.setBoolean(BlobServerOptions.SSL_ENABLED, false);
		nonSslClientConfig.setString(SecurityOptions.SSL_TRUSTSTORE, "src/test/resources/local127.truststore");
		nonSslClientConfig.setString(SecurityOptions.SSL_TRUSTSTORE_PASSWORD, "password");
	}

	/**
	 * Shuts the BLOB server down.
	 */
	@AfterClass
	public static void stopServers() throws IOException {
		if (BLOB_SSL_SERVER != null) {
			BLOB_SSL_SERVER.close();
		}
		if (BLOB_NON_SSL_SERVER != null) {
			BLOB_NON_SSL_SERVER.close();
		}
	}

	protected Configuration getBlobClientConfig() {
		return sslClientConfig;
	}

	protected BlobServer getBlobServer() {
		return BLOB_SSL_SERVER;
	}

	/**
	 * Verify ssl client to ssl server upload
	 */
	@Test
	public void testUploadJarFilesHelper() throws Exception {
		uploadJarFile(BLOB_SSL_SERVER, sslClientConfig);
	}

	/**
	 * Verify ssl client to non-ssl server failure
	 */
	@Test(expected = IOException.class)
	public void testSSLClientFailure() throws Exception {
		// SSL client connected to non-ssl server
		uploadJarFile(BLOB_SERVER, sslClientConfig);
	}

	/**
	 * Verify ssl client to non-ssl server failure
	 */
	@Test(expected = IOException.class)
	public void testSSLClientFailure2() throws Exception {
		// SSL client connected to non-ssl server
		uploadJarFile(BLOB_NON_SSL_SERVER, sslClientConfig);
	}

	/**
	 * Verify non-ssl client to ssl server failure
	 */
	@Test(expected = IOException.class)
	public void testSSLServerFailure() throws Exception {
		// Non-SSL client connected to ssl server
		uploadJarFile(BLOB_SSL_SERVER, clientConfig);
	}

	/**
	 * Verify non-ssl client to ssl server failure
	 */
	@Test(expected = IOException.class)
	public void testSSLServerFailure2() throws Exception {
		// Non-SSL client connected to ssl server
		uploadJarFile(BLOB_SSL_SERVER, nonSslClientConfig);
	}

	/**
	 * Verify non-ssl connection sanity
	 */
	@Test
	public void testNonSSLConnection() throws Exception {
		uploadJarFile(BLOB_SERVER, clientConfig);
	}

	/**
	 * Verify non-ssl connection sanity
	 */
	@Test
	public void testNonSSLConnection2() throws Exception {
		uploadJarFile(BLOB_SERVER, nonSslClientConfig);
	}

	/**
	 * Verify non-ssl connection sanity
	 */
	@Test
	public void testNonSSLConnection3() throws Exception {
		uploadJarFile(BLOB_NON_SSL_SERVER, clientConfig);
	}

	/**
	 * Verify non-ssl connection sanity
	 */
	@Test
	public void testNonSSLConnection4() throws Exception {
		uploadJarFile(BLOB_NON_SSL_SERVER, nonSslClientConfig);
	}
}
