/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.code.or;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import java.math.BigDecimal;
import java.math.BigInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.code.or.binlog.BinlogEventListener;
import com.google.code.or.binlog.BinlogParser;
import com.google.code.or.binlog.BinlogParserListener;
import com.google.code.or.binlog.impl.ReplicationBasedBinlogParser;
import com.google.code.or.binlog.impl.parser.DeleteRowsEventParser;
import com.google.code.or.binlog.impl.parser.DeleteRowsEventV2Parser;
import com.google.code.or.binlog.impl.parser.FormatDescriptionEventParser;
import com.google.code.or.binlog.impl.parser.IncidentEventParser;
import com.google.code.or.binlog.impl.parser.IntvarEventParser;
import com.google.code.or.binlog.impl.parser.QueryEventParser;
import com.google.code.or.binlog.impl.parser.RandEventParser;
import com.google.code.or.binlog.impl.parser.RotateEventParser;
import com.google.code.or.binlog.impl.parser.StopEventParser;
import com.google.code.or.binlog.impl.parser.TableMapEventParser;
import com.google.code.or.binlog.impl.parser.UpdateRowsEventParser;
import com.google.code.or.binlog.impl.parser.UpdateRowsEventV2Parser;
import com.google.code.or.binlog.impl.parser.UserVarEventParser;
import com.google.code.or.binlog.impl.parser.WriteRowsEventParser;
import com.google.code.or.binlog.impl.parser.WriteRowsEventV2Parser;
import com.google.code.or.binlog.impl.parser.XidEventParser;
import com.google.code.or.binlog.impl.parser.GtidEventParser;
import com.google.code.or.common.glossary.column.StringColumn;
import com.google.code.or.io.impl.SocketFactoryImpl;
import com.google.code.or.net.Packet;
import com.google.code.or.net.Transport;
import com.google.code.or.net.TransportException;
import com.google.code.or.net.impl.AuthenticatorImpl;
import com.google.code.or.net.impl.Query;
import com.google.code.or.net.impl.TransportImpl;
import com.google.code.or.net.impl.packet.ErrorPacket;
import com.google.code.or.net.impl.packet.command.ComBinlogDumpPacket;

/**
 *
 * @author Jingqi Xu
 * @author darnaut
 */
public class OpenReplicator {
	//
	protected int port = 3306;
	protected String host;
	protected String user;
	protected String password;
	protected int serverId = 6789;
	protected String binlogFileName;
	protected long binlogPosition = 4;
	protected String encoding = "utf-8";
	protected int level1BufferSize = 1024 * 1024;
	protected int level2BufferSize = 8 * 1024 * 1024;
	protected int socketReceiveBufferSize = 512 * 1024;
	protected Float heartbeatPeriod = null;

	//
	protected Transport transport;
	protected ReplicationBasedBinlogParser binlogParser;
	protected BinlogEventListener binlogEventListener;
	protected final AtomicBoolean running = new AtomicBoolean(false);

	private static final Logger LOGGER = LoggerFactory.getLogger(OpenReplicator.class);
	/**
	 *
	 */
	public boolean isRunning() {
		return this.running.get();
	}

	public void start() throws Exception {
		//
		if(!this.running.compareAndSet(false, true)) {
			return;
		}

		//
		if(this.transport == null) this.transport = getDefaultTransport();
		this.transport.connect(this.host, this.port);

		//
		if(this.binlogParser == null)
			this.binlogParser = getDefaultBinlogParser();

		setupChecksumState();
		setupHeartbeatPeriod();
		dumpBinlog();

		this.binlogParser.setBinlogFileName(this.binlogFileName);

		this.binlogParser.setEventListener(this.binlogEventListener);
		this.binlogParser.addParserListener(new BinlogParserListener.Adapter() {
			@Override
			public void onStop(BinlogParser parser) {
				stopQuietly(0, TimeUnit.MILLISECONDS);
			}
		});
		this.binlogParser.start();
	}

	public void stop(long timeout, TimeUnit unit) throws Exception {
		//
		if(!this.running.compareAndSet(true, false)) {
			return;
		}

		// disconnect the transport first: seems kinda wrong, but the parser thread can be blocked waiting for the
		// last event, and doesn't have any timeouts.  so we deal with the EOF exception thrown elsewhere in the code.
		this.transport.disconnect();

		this.binlogParser.stop(timeout, unit);
	}

	public void stopQuietly(long timeout, TimeUnit unit) {
		try {
			stop(timeout, unit);
		} catch(Exception e) {
			// NOP
		}
	}

	/**
	 *
	 */
	public int getPort() {
		return port;
	}

	public void setPort(int port) {
		this.port = port;
	}

	public String getHost() {
		return host;
	}

	public void setHost(String host) {
		this.host = host;
	}

	public String getUser() {
		return user;
	}

	public void setUser(String user) {
		this.user = user;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public String getEncoding() {
		return encoding;
	}

	public void setEncoding(String encoding) {
		this.encoding = encoding;
	}

	public int getServerId() {
		return serverId;
	}

	public void setServerId(int serverId) {
		this.serverId = serverId;
	}

	public long getBinlogPosition() {
		return binlogPosition;
	}

	public void setBinlogPosition(long binlogPosition) {
		this.binlogPosition = binlogPosition;
	}

	public String getBinlogFileName() {
		return binlogFileName;
	}

	public void setBinlogFileName(String binlogFileName) {
		this.binlogFileName = binlogFileName;
	}

	public int getLevel1BufferSize() {
		return level1BufferSize;
	}

	public void setLevel1BufferSize(int level1BufferSize) {
		this.level1BufferSize = level1BufferSize;
	}

	public int getLevel2BufferSize() {
		return level2BufferSize;
	}

	public void setLevel2BufferSize(int level2BufferSize) {
		this.level2BufferSize = level2BufferSize;
	}

	public int getSocketReceiveBufferSize() {
		return socketReceiveBufferSize;
	}

	public void setSocketReceiveBufferSize(int socketReceiveBufferSize) {
		this.socketReceiveBufferSize = socketReceiveBufferSize;
	}

	/**
	 *
	 */
	public Transport getTransport() {
		return transport;
	}

	public void setTransport(Transport transport) {
		this.transport = transport;
	}

	public BinlogParser getBinlogParser() {
		return binlogParser;
	}

	public void setBinlogParser(ReplicationBasedBinlogParser parser) {
		this.binlogParser = parser;
	}

	public BinlogEventListener getBinlogEventListener() {
		return binlogEventListener;
	}

	public void setBinlogEventListener(BinlogEventListener listener) {
		this.binlogEventListener = listener;
	}

	protected void setupChecksumState() throws Exception {
		final Query query = new Query(this.transport);

		try {
			List<String> cols = query.getFirst("SELECT @@global.binlog_checksum");

			if ( cols != null && cols.get(0).equals("CRC32") || cols.get(0).equals("NONE")) {
				query.getFirst("SET @master_binlog_checksum = @@global.binlog_checksum");
			}
		} catch ( TransportException e ) {
			if ( e.getErrorCode() != 1193 ) // ignore no-such-variable errors on mysql 5.5
				throw e;
		}
	}

	protected void setupHeartbeatPeriod() throws Exception {
		if ( this.heartbeatPeriod == null )
			return;

		BigInteger nanoSeconds = BigDecimal.valueOf(1000000000).multiply(BigDecimal.valueOf(this.heartbeatPeriod)).toBigInteger();

		final Query query = new Query(this.transport);
		query.getFirst("SET @master_heartbeat_period = " + nanoSeconds);
	}

	public void setHeartbeatPeriod(float period) {
		this.heartbeatPeriod = period;
	}

	public Float getHeartbeatPeriod() {
		return this.heartbeatPeriod;
	}

	public long getHeartbeatCount() {
		return binlogParser.getHeartbeatCount();
	}

	public Long millisSinceLastEvent() {
		return binlogParser.millisSinceLastEvent();
	}

	/**
	 *
	 */
	protected void dumpBinlog() throws Exception {
		//
		final ComBinlogDumpPacket command = new ComBinlogDumpPacket();
		command.setBinlogFlag(0);
		command.setServerId(this.serverId);
		command.setBinlogPosition(this.binlogPosition);
		command.setBinlogFileName(StringColumn.valueOf(this.binlogFileName.getBytes(this.encoding)));
		this.transport.getOutputStream().writePacket(command);
		this.transport.getOutputStream().flush();

		//
		final Packet packet = this.transport.getInputStream().readPacket();
		if(packet.getPacketBody()[0] == ErrorPacket.PACKET_MARKER) {
			final ErrorPacket error = ErrorPacket.valueOf(packet);
			throw new TransportException(error);
		}
	}

	protected Transport getDefaultTransport() throws Exception {
		//
		final TransportImpl r = new TransportImpl();
		r.setLevel1BufferSize(this.level1BufferSize);
		r.setLevel2BufferSize(this.level2BufferSize);

		//
		final AuthenticatorImpl authenticator = new AuthenticatorImpl();
		authenticator.setUser(this.user);
		authenticator.setPassword(this.password);
		authenticator.setEncoding(this.encoding);
		r.setAuthenticator(authenticator);

		//
		final SocketFactoryImpl socketFactory = new SocketFactoryImpl();
		socketFactory.setKeepAlive(true);
		socketFactory.setTcpNoDelay(false);
		socketFactory.setReceiveBufferSize(this.socketReceiveBufferSize);
		r.setSocketFactory(socketFactory);
		return r;
	}

	protected ReplicationBasedBinlogParser getDefaultBinlogParser() throws Exception {
		//
		final ReplicationBasedBinlogParser r = new ReplicationBasedBinlogParser();
		r.registerEventParser(new StopEventParser());
		r.registerEventParser(new RotateEventParser());
		r.registerEventParser(new IntvarEventParser());
		r.registerEventParser(new XidEventParser());
		r.registerEventParser(new RandEventParser());
		r.registerEventParser(new QueryEventParser());
		r.registerEventParser(new UserVarEventParser());
		r.registerEventParser(new IncidentEventParser());
		r.registerEventParser(new TableMapEventParser());
		r.registerEventParser(new WriteRowsEventParser());
		r.registerEventParser(new UpdateRowsEventParser());
		r.registerEventParser(new DeleteRowsEventParser());
		r.registerEventParser(new WriteRowsEventV2Parser());
		r.registerEventParser(new UpdateRowsEventV2Parser());
		r.registerEventParser(new DeleteRowsEventV2Parser());
		r.registerEventParser(new FormatDescriptionEventParser());
		r.registerEventParser(new GtidEventParser());

		//
		r.setTransport(this.transport);
		return r;
	}
}
