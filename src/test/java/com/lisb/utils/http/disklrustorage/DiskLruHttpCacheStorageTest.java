package com.lisb.utils.http.disklrustorage;

import java.io.File;
import java.util.Date;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import ch.boye.httpclientandroidlib.Header;
import ch.boye.httpclientandroidlib.HttpResponse;
import ch.boye.httpclientandroidlib.ProtocolVersion;
import ch.boye.httpclientandroidlib.StatusLine;
import ch.boye.httpclientandroidlib.client.HttpClient;
import ch.boye.httpclientandroidlib.client.cache.CacheResponseStatus;
import ch.boye.httpclientandroidlib.client.cache.HttpCacheEntry;
import ch.boye.httpclientandroidlib.client.cache.Resource;
import ch.boye.httpclientandroidlib.client.methods.HttpGet;
import ch.boye.httpclientandroidlib.client.methods.HttpUriRequest;
import ch.boye.httpclientandroidlib.impl.client.DefaultHttpClient;
import ch.boye.httpclientandroidlib.impl.client.cache.CacheConfig;
import ch.boye.httpclientandroidlib.impl.client.cache.CachingHttpClient;
import ch.boye.httpclientandroidlib.impl.client.cache.HeapResource;
import ch.boye.httpclientandroidlib.message.BasicHeader;
import ch.boye.httpclientandroidlib.message.BasicStatusLine;
import ch.boye.httpclientandroidlib.protocol.BasicHttpContext;
import ch.boye.httpclientandroidlib.protocol.HttpContext;
import ch.boye.httpclientandroidlib.util.EntityUtils;

public class DiskLruHttpCacheStorageTest {

	private static DiskLruHttpCacheStorage storage;

	@BeforeClass
	public static void beforeClass() throws Exception {
		final File dir = new File("cache");
		final int maxSize = 10 * 1000 * 1000;
		storage = new DiskLruHttpCacheStorage(dir, maxSize);
	}

	@AfterClass
	public static void afterClass() throws Exception {
		storage.delete();
		storage.close();
	}

	@Test
	public void testGetAndPut() throws Exception {
		final long now = System.currentTimeMillis();
		final Date requestDate = new Date(now - 34567);
		final Date responseDate = new Date(now - 12345);
		final StatusLine statusLine = new BasicStatusLine(new ProtocolVersion(
				"protocol", 234, 123), 200, "OK");
		final Header[] responseHeaders = new Header[2];
		responseHeaders[0] = new BasicHeader("key0", "hogehoge");
		responseHeaders[1] = new BasicHeader("key1", "fugafuga");
		final Resource resource = new HeapResource(new byte[] { 5, 4, 3, 2, 1 });
		final HttpCacheEntry inputEntry = new HttpCacheEntry(requestDate,
				responseDate, statusLine, responseHeaders, resource);
		final String key = "key0";
		storage.putEntry(key, inputEntry);

		final HttpCacheEntry outputEntry = storage.getEntry(key);
		assertEquals(inputEntry, outputEntry);
	}

	@Test
	public void testHttpGet() throws Exception {
		final CachingHttpClient hc = createCachingHttpClient();

		final HttpUriRequest request1 = new HttpGet(
				"http://odaiba.rocketserver.jp/wordpress/wp-content/uploads/2012/12/LisB_logo.png");
		final HttpContext context1 = new BasicHttpContext();
		final HttpResponse response1 = hc.execute(request1, context1);
		EntityUtils.consume(response1.getEntity());
		final CacheResponseStatus responseStatus1 = (CacheResponseStatus) context1
				.getAttribute(CachingHttpClient.CACHE_RESPONSE_STATUS);
		Assert.assertEquals(CacheResponseStatus.CACHE_MISS, responseStatus1);

		final HttpUriRequest request2 = new HttpGet(
				"http://odaiba.rocketserver.jp/wordpress/wp-content/uploads/2012/12/LisB_logo.png");
		final HttpContext context2 = new BasicHttpContext();
		final HttpResponse response2 = hc.execute(request2, context2);
		EntityUtils.consume(response2.getEntity());
		final CacheResponseStatus responseStatus2 = (CacheResponseStatus) context2
				.getAttribute(CachingHttpClient.CACHE_RESPONSE_STATUS);
		Assert.assertEquals(CacheResponseStatus.CACHE_HIT, responseStatus2);
	}

	private CachingHttpClient createCachingHttpClient() {
		final HttpClient base = new DefaultHttpClient();
		final CacheConfig config = new CacheConfig();
		config.setHeuristicCachingEnabled(true);
		final CachingHttpClient hc = new CachingHttpClient(base, storage,
				config);

		return hc;
	}

	private void assertEquals(HttpCacheEntry expected, HttpCacheEntry actual) {
		Assert.assertEquals(expected.getRequestDate(), actual.getRequestDate());
		Assert.assertEquals(expected.getResponseDate(),
				actual.getResponseDate());
		assertEquals(expected.getStatusLine(), actual.getStatusLine());
		assertEquals(expected.getAllHeaders(), actual.getAllHeaders());
		Assert.assertEquals(expected.getVariantMap(), actual.getVariantMap());
	}

	private void assertEquals(final StatusLine expected, final StatusLine actual) {
		Assert.assertEquals(expected.getProtocolVersion(),
				actual.getProtocolVersion());
		Assert.assertEquals(expected.getStatusCode(), actual.getStatusCode());
		Assert.assertEquals(expected.getReasonPhrase(),
				actual.getReasonPhrase());
	}

	private void assertEquals(final Header[] expected, final Header[] actual) {
		Assert.assertEquals(expected.length, actual.length);
		for (int i = 0, size = expected.length; i < size; i++) {
			Assert.assertEquals("header[" + i + "]'s name is different.",
					expected[i].getName(), actual[i].getName());
			Assert.assertEquals("header[" + i + "]'s value is different.",
					expected[i].getValue(), actual[i].getValue());
		}
	}

}
