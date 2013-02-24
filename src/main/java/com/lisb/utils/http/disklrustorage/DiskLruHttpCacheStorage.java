package com.lisb.utils.http.disklrustorage;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.security.MessageDigest;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import ch.boye.httpclientandroidlib.Header;
import ch.boye.httpclientandroidlib.ProtocolVersion;
import ch.boye.httpclientandroidlib.StatusLine;
import ch.boye.httpclientandroidlib.annotation.ThreadSafe;
import ch.boye.httpclientandroidlib.client.cache.HttpCacheEntry;
import ch.boye.httpclientandroidlib.client.cache.HttpCacheStorage;
import ch.boye.httpclientandroidlib.client.cache.HttpCacheUpdateCallback;
import ch.boye.httpclientandroidlib.client.cache.HttpCacheUpdateException;
import ch.boye.httpclientandroidlib.client.cache.Resource;
import ch.boye.httpclientandroidlib.message.BasicHeader;
import ch.boye.httpclientandroidlib.message.BasicStatusLine;

import com.jakewharton.DiskLruCache;
import com.jakewharton.DiskLruCache.Editor;
import com.jakewharton.DiskLruCache.Snapshot;
import com.lisb.utils.http.disklrustorage.compat.Charsets;
import com.lisb.utils.http.disklrustorage.compat.MD5;
import com.lisb.utils.http.disklrustorage.compat.Strings;

/**
 * {@link DiskLruCache} をバックグランドにもつ {@link HttpCacheStorage}。
 */
// スレッドセーフ性はDiskLruCacheのスレッドセーフ性に依存しているところが大きい。
// DiskLruCache はスレッドセーフだと明記されていないが、
// 実装や利用方法をよんでスレッドセーフに作られていると判断した。
// この前提がくずれると、このクラス自体に同期機構を組み込む必要があるので注意。
@ThreadSafe
public class DiskLruHttpCacheStorage implements HttpCacheStorage {

	public static final int VERSION = 1;

	/* DiskLruCacheのインデックス */
	private static final int ENTRY_METADATA = 0;
	private static final int ENTRY_BODY = 1;
	private static final int ENTRY_COUNT = 2;

	private final DiskLruCache diskLruCache;

	public DiskLruHttpCacheStorage(final File directory, final long maxSize)
			throws IOException {
		diskLruCache = DiskLruCache.open(directory, VERSION, ENTRY_COUNT,
				maxSize);
	}

	public void flush() throws IOException {
		diskLruCache.flush();
	}

	public void delete() throws IOException {
		diskLruCache.delete();
	}

	public void close() throws IOException {
		diskLruCache.close();
	}

	public HttpCacheEntry getEntry(String key) throws IOException {
		key = uriToKey(key);
		// TODO entryのrequest headerの値を利用側でチェックしているか確認し、
		// チェックしていない場合、こちらでチェックする。
		Snapshot snapshot = null;
		try {
			snapshot = diskLruCache.get(key);
			if (snapshot == null) {
				return null;
			}
			final HttpCacheEntry entry = readFrom(snapshot);
			return entry;
		} catch (IOException e) {
			snapshot.close();
			throw e;
		}
	}

	public void putEntry(String key, HttpCacheEntry entry) throws IOException {
		key = uriToKey(key);
		Editor editor = diskLruCache.edit(key);
		if (editor == null) {
			return;
		}
		writeTo(editor, entry);
	}

	public void removeEntry(String key) throws IOException {
		key = uriToKey(key);
		diskLruCache.remove(key);
	}

	public void updateEntry(String key, HttpCacheUpdateCallback callback)
			throws IOException, HttpCacheUpdateException {
		key = uriToKey(key);
		final HttpCacheEntry existing = getEntry(key);
		final HttpCacheEntry updating = callback.update(existing);
		putEntry(key, updating);
	}

	private String uriToKey(final String uri) {
		// try {
		// MessageDigest.getInstance(String) isn't thread safe, but it should
		// be.
		// On Android, if that static method is invoked by multiple threads
		// simultaneously,
		// a ConcurrentModificationException is thrown. This affects only
		// Android -
		// Sun/Oracle/Open JREs all work correctly.
		// see https://code.google.com/p/android/issues/detail?id=37937
		// So use our own MD5 implementation instead.
		MessageDigest messageDigest = new MD5();
		byte[] md5bytes = messageDigest.digest(Strings.getBytes(uri.toString(),
				Charsets.UTF_8));
		return Strings.bytesToHexString(md5bytes, false);
		// } catch (NoSuchAlgorithmException e) {
		// throw new AssertionError(e);
		// }
	}

	// ====== DiskLruCacheからの読込 ===== //

	private HttpCacheEntry readFrom(final Snapshot snapshot) throws IOException {
		final Date requestDate;
		final Date responseDate;
		final StatusLine statusLine;
		final Header[] responseHeaders;
		final Map<String, String> variantMap;
		BufferedInputStream in = null;
		try {
			in = new BufferedInputStream(
					snapshot.getInputStream(ENTRY_METADATA));
			requestDate = new Date(Long.valueOf(Streams.readAsciiLine(in)));
			responseDate = new Date(Long.valueOf(Streams.readAsciiLine(in)));
			statusLine = readStatusLine(in);
			responseHeaders = readResponseHeaders(in);
			variantMap = readVariantMap(in);
		} finally {
			if (in != null) {
				in.close();
			}
		}

		final Resource resource = readResource(snapshot);

		return new HttpCacheEntry(requestDate, responseDate, statusLine,
				responseHeaders, resource, variantMap);
	}

	private StatusLine readStatusLine(final InputStream in) throws IOException {
		final ProtocolVersion version = readProtocolVersion(in);
		final int statusCode = Integer.valueOf(Streams.readAsciiLine(in));
		final String reasonPhrase = Streams.readAsciiLine(in);
		return new BasicStatusLine(version, statusCode, reasonPhrase);
	}

	private ProtocolVersion readProtocolVersion(final InputStream in)
			throws IOException {
		final String protocol = Streams.readAsciiLine(in);
		final int majorProtocolVersion = Integer.valueOf(Streams
				.readAsciiLine(in));
		final int minorProtocolVersion = Integer.valueOf(Streams
				.readAsciiLine(in));
		return new ProtocolVersion(protocol, majorProtocolVersion,
				minorProtocolVersion);
	}

	private Header[] readResponseHeaders(final InputStream in)
			throws IOException {
		final int headerCount = Integer.valueOf(Streams.readAsciiLine(in));
		final Header[] headers = new Header[headerCount];
		for (int i = 0; i < headerCount; i++) {
			final String key = Streams.readAsciiLine(in);
			final String value = Streams.readAsciiLine(in);
			headers[i] = new BasicHeader(key, value);
		}

		return headers;
	}

	private Map<String, String> readVariantMap(final InputStream in)
			throws IOException {
		final int mapSize = Integer.valueOf(Streams.readAsciiLine(in));
		final Map<String, String> map = new HashMap<String, String>(mapSize * 2);
		for (int i = 0; i < mapSize; i++) {
			final String key = Streams.readAsciiLine(in);
			final String value = Streams.readAsciiLine(in);
			map.put(key, value);
		}

		return map;
	}

	private Resource readResource(final Snapshot snapshot) {
		return new Resource() {
			private static final long serialVersionUID = -3869776330328527339L;

			public long length() {
				return snapshot.getLength(ENTRY_BODY);
			}

			public InputStream getInputStream() throws IOException {
				// FileResourceやHeapResourceは呼び出し毎にStreamを生成しているので
				// データをすべてbyte[]にはき出して保持しておく必要があるかも。
				return snapshot.getInputStream(ENTRY_BODY);
			}

			public void dispose() {
				snapshot.close();
			}
		};
	}

	// ===== DiskLruCache への書き込み ===== //

	private void writeTo(final Editor editor, final HttpCacheEntry entry)
			throws IOException {
		try {
			writeMetadataTo(editor, entry);
			writeBodyTo(editor, entry.getResource());
			editor.commit();
		} catch (IOException e) {
			editor.abort();
			throw e;
		}
	}

	private void writeMetadataTo(final Editor editor, final HttpCacheEntry entry)
			throws IOException {
		OutputStream stream = null;
		Writer writer = null;
		Writer bufferedWriter = null;
		try {
			stream = editor.newOutputStream(ENTRY_METADATA);
			writer = new OutputStreamWriter(stream, Charsets.UTF_8);
			bufferedWriter = new BufferedWriter(writer);

			bufferedWriter.write(Long
					.toString(entry.getRequestDate().getTime()));
			bufferedWriter.write('\n');
			bufferedWriter.write(Long.toString(entry.getResponseDate()
					.getTime()));
			bufferedWriter.write('\n');
			writeStatusLine(bufferedWriter, entry.getStatusLine());
			writeResponseHeaders(bufferedWriter, entry.getAllHeaders());
			writeVariantMap(bufferedWriter, entry.getVariantMap());
		} finally {
			if (bufferedWriter != null) {
				bufferedWriter.close();
			}

			if (writer != null) {
				writer.close();
			}

			if (stream != null) {
				stream.close();
			}
		}
	}

	private void writeStatusLine(final Writer writer,
			final StatusLine statusLine) throws IOException {
		writeProtocolVersion(writer, statusLine.getProtocolVersion());
		writer.write(Integer.toString(statusLine.getStatusCode()));
		writer.write('\n');
		writer.write(statusLine.getReasonPhrase());
		writer.write('\n');
	}

	private void writeProtocolVersion(final Writer writer,
			final ProtocolVersion protocolVersion) throws IOException {
		writer.write(protocolVersion.getProtocol());
		writer.write('\n');
		writer.write(Integer.toString(protocolVersion.getMajor()));
		writer.write('\n');
		writer.write(Integer.toString(protocolVersion.getMinor()));
		writer.write('\n');
	}

	private void writeResponseHeaders(final Writer writer,
			final Header[] headers) throws IOException {
		writer.write(Integer.toString(headers.length));
		writer.write('\n');
		for (final Header header : headers) {
			writer.write(header.getName());
			writer.write('\n');
			writer.write(header.getValue());
			writer.write('\n');
		}
	}

	private void writeVariantMap(final Writer writer,
			final Map<String, String> variantMap) throws IOException {
		writer.write(Integer.toString(variantMap.size()));
		writer.write('\n');
		for (Entry<String, String> entry : variantMap.entrySet()) {
			writer.write(entry.getKey());
			writer.write('\n');
			writer.write(entry.getValue());
			writer.write('\n');
		}
	}

	private void writeBodyTo(final Editor editor, final Resource resource)
			throws IOException {
		OutputStream out = null;
		BufferedOutputStream bout = null;
		InputStream in = null;
		BufferedInputStream bin = null;
		try {
			out = editor.newOutputStream(ENTRY_BODY);
			bout = new BufferedOutputStream(out);
			in = resource.getInputStream();
			bin = new BufferedInputStream(in);

			int data = 0;
			while ((data = bin.read()) != -1) {
				bout.write(data);
			}
		} finally {
			if (bout != null) {
				bout.close();
			}

			if (out != null) {
				out.close();
			}

			if (bin != null) {
				bin.close();
			}

			if (in != null) {
				in.close();
			}
		}

	}
}
