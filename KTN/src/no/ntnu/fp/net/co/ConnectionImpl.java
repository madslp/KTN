/*
 * Created on Oct 27, 2004
 */
package no.ntnu.fp.net.co;

import java.io.EOFException;
import java.io.IOException;
import java.net.BindException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.management.RuntimeErrorException;

import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import no.ntnu.fp.net.admin.Log;
import no.ntnu.fp.net.cl.ClException;
import no.ntnu.fp.net.cl.ClSocket;
import no.ntnu.fp.net.cl.KtnDatagram;
import no.ntnu.fp.net.cl.KtnDatagram.Flag;

/**
 * Implementation of the Connection-interface. <br>
 * <br>
 * This class implements the behavior in the methods specified in the interface
 * {@link Connection} over the unreliable, connection-less network realized in
 * {@link ClSocket}. The base class, {@link AbstractConnection} implements some
 * of the functionality, leaving message passing and error handling to this
 * implementation.
 * 
 * @author Sebjørn Birkeland and Stein Jakob Nordbø
 * @see no.ntnu.fp.net.co.Connection
 * @see no.ntnu.fp.net.cl.ClSocket
 */
public class ConnectionImpl extends AbstractConnection {

    /** Keeps track of the used ports for each server port. */
    private static Map<Integer, Boolean> usedPorts = Collections.synchronizedMap(new HashMap<Integer, Boolean>());

    /**
     * Initialize initial sequence number and setup state machine.
     * 
     * @param myPort
     *            - the local port to associate with this connection
     */
    public ConnectionImpl(int myPort) {
    	super();
    	
    	if(usedPorts.containsKey((Integer) myPort))
    	//	throw new java.net.BindException("Cannot bind to port " + myPort + ". Already in use.");
    		throw new IllegalArgumentException("Cannot bind to port " + myPort + ". Already in use.");
    	
    	usedPorts.put(myPort, true);
    	
    	this.myPort = myPort;
    	this.myAddress = getIPv4Address();
    }

    private String getIPv4Address() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        }
        catch (UnknownHostException e) {
            return "127.0.0.1";
        }
    }

    /**
     * Establish a connection to a remote location.
     * 
     * @param remoteAddress
     *            - the remote IP-address to connect to
     * @param remotePort
     *            - the remote portnumber to connect to
     * @throws IOException
     *             If there's an I/O error.
     * @throws java.net.SocketTimeoutException
     *             If timeout expires before connection is completed.
     * @see Connection#connect(InetAddress, int)
     */
    public void connect(InetAddress remoteAddress, int remotePort) throws IOException,
            SocketTimeoutException {
    	assert this.state == State.CLOSED:
    		new IllegalStateException("Only a CLOSED Connection can connect().");
    	
    	this.remoteAddress = remoteAddress.getHostAddress();
    	this.remotePort = remotePort;
    	this.myPort = findFreePort(); // TODO: add this port to usedPorts

    	KtnDatagram syn = constructInternalPacket(Flag.SYN);

    	System.err.println("Sending SYN!");
    	
    	this.state = State.SYN_SENT;
    	KtnDatagram ack = sendDataPacketWithRetransmit(syn);

    	if(ack == null)
    		throw new SocketTimeoutException("connect() timed out.");
    	
    	System.err.println("Got SYN_ACK!, ACKing.");
    	
    	this.remotePort = ack.getSrc_port();
    	sendAck(ack, false);

    	System.err.println("ESTABLISHED");
    	this.state = State.ESTABLISHED;
    }

    /**
     * Listen for, and accept, incoming connections.
     * 
     * @return A new ConnectionImpl-object representing the new connection.
     * @see Connection#accept()
     */
    public Connection accept() throws IOException, SocketTimeoutException {
    	assert this.state == State.CLOSED:
    		new IllegalStateException("Only a CLOSED Connection can accept().");
    	
    	this.state = State.LISTEN;
    	System.err.println("LISTENING");
    	ConnectionImpl connection;
    	do {
    		KtnDatagram syn = waitForPacket(Flag.SYN);

    		connection = new ConnectionImpl(findFreePort());
    		connection.syncConnection(syn);
    	} while (connection == null);
    	
    	System.err.println("Got new connection");
    	this.state = State.CLOSED;
    	return connection;
    }
    
    private KtnDatagram waitForPacket(Flag flag) throws EOFException, IOException{
    	KtnDatagram packet;
		do {
			packet = receivePacket(flag != Flag.NONE);
			if(packet != null)
				System.err.println("Got someting..");
		} while (  packet == null || packet.getFlag() != flag  );
		
		System.err.println("It's the right thing!");
		return packet;
    }
    
    private int findFreePort(){
    	int port;
    	do {
    		port = (int) (Math.random() * (Math.pow(2, 16)-1 - 20000)) + 20000;
    	} while (usedPorts.containsKey(port));
    	return port;
    }
    
    private Connection syncConnection(KtnDatagram syn) throws IOException{
    	assert this.state == State.CLOSED;
    	
    	System.err.println("Got SYN.");
    	this.state = State.SYN_RCVD;
    	
    	this.remoteAddress = syn.getSrc_addr();
    	this.remotePort = syn.getSrc_port();
    	this.lastValidPacketReceived = syn;
    	
    	System.err.println("Sending SYN_ACK.");
    	KtnDatagram synAck = constructInternalPacket(Flag.SYN_ACK);
    	KtnDatagram ack = sendDataPacketWithRetransmit(synAck);
    	
    	if(ack == null)
    		return null;
    	
    	System.err.println("Got ACK. ESTABLISHED");
    	
    	this.state = State.ESTABLISHED;
    	
		return this;
    	
    }

    /**
     * Send a message from the application.
     * 
     * @param msg
     *            - the String to be sent.
     * @throws ConnectException
     *             If no connection exists.
     * @throws IOException
     *             If no ACK was received.
     * @see AbstractConnection#sendDataPacketWithRetransmit(KtnDatagram)
     * @see no.ntnu.fp.net.co.Connection#send(String)
     */
    public void send(String msg) throws ConnectException, IOException {
    	assert this.state == State.ESTABLISHED:
    		new IllegalStateException("Can only send() in ESTABLISHED state.");
    	System.err.println("Sending data..");
        KtnDatagram ack = sendDataPacketWithRetransmit(constructDataPacket(msg));

        if(ack == null)
        	throw new IOException("send() timed out.");
        
        System.err.println("Got ACK for data.");
    }

    /**
     * Wait for incoming data.
     * 
     * @return The received data's payload as a String.
     * @see Connection#receive()
     * @see AbstractConnection#receivePacket(boolean)
     * @see AbstractConnection#sendAck(KtnDatagram, boolean)
     */
    public String receive() throws ConnectException, IOException {
    	KtnDatagram packet;
    	System.err.println("Receiving..");
    	try {
    		do {
    			System.err.println("Waiting for valid packet..");
    			packet = waitForPacket(Flag.NONE);
    		} while (!isValid(packet));
    		System.err.println("Got data. Sending ACK.");
    		sendAck(packet, false);
    		this.lastValidPacketReceived = packet;
    		return (String) packet.getPayload();
    	} catch (EOFException e){
    		System.err.println("Got FIN. CLOSE_WAIT.");
    		sendAck(this.disconnectRequest, false);
    		this.state = State.CLOSE_WAIT;
    		throw e;
    	}
    }

    /**
     * Close the connection.
     * 
     * @see Connection#close()
     */
    public void close() throws IOException {
    	assert this.state == State.ESTABLISHED || this.state == State.CLOSE_WAIT:
    		new IllegalStateException("Can only close open sockets.");
    	
    	System.err.println("close() called.");

    	if(this.state == State.ESTABLISHED){
    		this.state = State.FIN_WAIT_1;
    		
    		System.err.println("Sending FIN.");
    		KtnDatagram lfin = constructInternalPacket(Flag.FIN);
    		KtnDatagram lack = sendDataPacketWithRetransmit(lfin);
    		
    		if(lack == null)
    			throw new IOException("Timed out.");
    		
    		this.state = State.FIN_WAIT_2;
    		
    		System.err.println("Waiting for FIN.");
    		KtnDatagram rfin = waitForPacket(Flag.FIN);
    		sendAck(rfin, false);
    		
    		this.state = State.TIME_WAIT;
    	}
        
    	if(this.state == State.CLOSE_WAIT){
    		this.state = State.LAST_ACK;

    		System.err.println("Sending FIN.");
    		KtnDatagram lfin = constructInternalPacket(Flag.FIN);
    		sendDataPacketWithRetransmit(lfin);
    		
    	}
        
		// TODO: remove used port
		this.state = State.CLOSED;
    	System.err.println("CLOSED");
    }

    /**
     * Test a packet for transmission errors. This function should only called
     * with data or ACK packets in the ESTABLISHED state.
     * 
     * @param packet
     *            Packet to test.
     * @return true if packet is free of errors, false otherwise.
     */
    protected boolean isValid(KtnDatagram packet) {
    	boolean valid =
    				packet.getChecksum() == packet.calculateChecksum()
    				;
    	
    	return valid;
    }
}
