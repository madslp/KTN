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

    private KtnDatagram confirmAck(Flag flag) throws EOFException, IOException{
    	KtnDatagram packet;
    	// Problem: any garbage received will delay the timeout
    	// TODO: Add timer checks
    	do{
    		packet = receiveAck();
        	if(packet == null)
        		throw new SocketTimeoutException("Timed out.");
    	} while (  !(packet.getFlag() == flag && isValid(packet))  );
    	return packet;
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
    	this.myPort = findFreePort();
    	
    	KtnDatagram packet;
    
    	packet = constructInternalPacket(Flag.SYN);
    	try {
			simplySendPacket(packet);
		} catch (ClException e) {
			throw new IOException("No route to host.", e);
		} catch (ConnectException e) {
			throw new IOException("Connection refused.", e);
		}
    	
    	this.state = State.SYN_SENT;
    	
    	packet = confirmAck(Flag.SYN_ACK);

    	this.remoteAddress = packet.getSrc_addr();
    	this.remotePort = packet.getSrc_port();
    	
    	sleep(400);
    	
    	try{
    		sendAck(packet, false);
    	} catch(ConnectException e){
    		System.err.println("Big phail!");
    		this.state = State.CLOSED;
    		throw new IOException("Could not send final ACK in connect().", e);
    	}

    	this.state = State.ESTABLISHED;
    }
    
    void sleep(long millis){
    	try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {System.err.println("Sleep interupted.");}
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
    	
    	KtnDatagram packet;
    	
    	do{
    		packet = receivePacket(true);
    	} while ( packet == null || !(packet.getFlag() == Flag.SYN && isValid(packet))  );

    	ConnectionImpl connection = new ConnectionImpl(findFreePort());
    	connection.syncConnection(packet);
    	
    	this.state = State.CLOSED;
    	return connection;
    }
    
    private int findFreePort(){
    	int port;
    	do{
    		port = (int) (Math.random() * (Math.pow(2, 16) - 20000)) + 20000;
    	} while (usedPorts.containsKey(port));
    	return port;
    }
    
    private Connection syncConnection(KtnDatagram synPacket) throws IOException{
    	assert this.state == State.CLOSED;
    	this.state = State.SYN_RCVD;
    	
    	this.remoteAddress = synPacket.getSrc_addr();
    	this.remotePort = synPacket.getSrc_port();
    	    	
    	try{
    		sendAck(synPacket, true);
    	} catch(ConnectException e){
    		System.out.println("SYN_ACK not sent.");
    		// Too bad. Shit can time out.
    	}
    	
    	confirmAck(Flag.ACK);
    	
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
        sendDataPacketWithRetransmit(constructDataPacket(msg));
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
    	try {
    		packet = receivePacket(false);
    	} catch (EOFException e){
    		this.state = State.CLOSE_WAIT;
    		throw e;
    	}
    	sendAck(packet, false);
        return (String) packet.getPayload();
    }

    /**
     * Close the connection.
     * 
     * @see Connection#close()
     */
    public void close() throws IOException {
    	assert this.state == State.ESTABLISHED || this.state == State.CLOSE_WAIT:
    		new IllegalStateException("Can only close open sockets.");
    	
    	if(this.state == State.ESTABLISHED)
    		this.state = State.FIN_WAIT_1;
    	
    	if(this.state == State.CLOSE_WAIT){
    		this.state = State.LAST_ACK;
    		this.state = State.CLOSED;
    	}
    	
        KtnDatagram packet = constructInternalPacket(Flag.FIN);
        sleep(200);
        try {
			simplySendPacket(packet);
		} catch (ClException e) {
			throw new IOException(e);
		}
        
        // We should probably wait for an ACK and retransmit fin if necessary.
        // However, a fin could come in before an ACK if both parties device to
        // close connection independently.
        
        if(this.state == State.FIN_WAIT_1){
        	int retry = 40;
        	do {
        		packet = receivePacket(true);
        		if(packet == null && retry --== 0){
        			throw new IOException("Timed out.");
        		}
        	} while(packet == null || packet.getFlag() != Flag.FIN);
        	sendAck(packet, false);
        	this.state = State.TIME_WAIT;
        	this.state = State.CLOSED;
        }
        
        
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
    	return true;
    }
}
