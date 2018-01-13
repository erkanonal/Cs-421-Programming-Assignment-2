import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;
import java.util.concurrent.Semaphore;

public class GBNSender {	

	private static String file_path;
	//private String path_destination = "C:/Users/ERKAN-PC/Desktop/cs-421/programming assignment 2/test.bin";
	
	//private static String receiver_ip_address = "139.179.226.40";
	private static String receiver_ip_address;
	private static int receiver_port;
	private static int window_size_N;
	private static final int packetDataSize = 1024;
	private static final int packetSize = 1026;
	private static long timeout;
	
	private static FileInputStream fstrm = null;
	private static int fileSize;
	private static int numberOfPackets;
	private Vector<byte[]> sentPackets;				//Vector is used as alternative to ArrayList, because of its synchronization advantage
	//private ArrayList<byte[]> sentPackets;	
	
	private Timer timer;
	private int baseSeqNum;
	private int curSeqNum;
	boolean isTransferOver;
	
	private static long startTime;
	private static long endTime;
	//Semaphore is used to protect the shared variable curSeqNum and isTransferOver variables
	private final Semaphore lock;
	
	public GBNSender() {
		DatagramSocket clientSocket = null;	
		//DatagramSocket listenSocket = null;	
		try {
			FileInputStream fs = new FileInputStream(file_path);
			fileSize = fs.available();
			numberOfPackets = fileSize/1024;
		} catch (FileNotFoundException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		baseSeqNum = 1;
		curSeqNum = 1;
		//
		sentPackets = new Vector<byte[]>(window_size_N);
		//sentPackets = new ArrayList<byte[]>;
		isTransferOver = false;
		lock = new Semaphore(1);
		
		try {
			clientSocket = new DatagramSocket();
		} catch (SocketException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		PacketSender packetSender = new PacketSender(clientSocket);
		ACKListener ackListener = new ACKListener(clientSocket);
		
		packetSender.start();
		ackListener.start();
	}
	
	public class PacketSender extends Thread {
		
		private DatagramSocket clientSocket;
		private DatagramPacket packetOut;
		private byte[] dataRead;
		private byte[] dataSeq;
		//private byte[] dataOut;
		
		public PacketSender(DatagramSocket clientSocket) {
			this.clientSocket = clientSocket;
			this.packetOut = null;
			this.dataRead = null;
			this.dataSeq = null;
			//this.dataOut = null;
		}
		
		public byte[] createPacket(int sequenceNumber, byte[] dataRead) {
			byte[] dataSeq = new byte[2];		
			dataSeq[0] = (byte) ((sequenceNumber >> 8) & 0xFF);
            dataSeq[1] = (byte) (sequenceNumber & 0xFF);
            
            byte[] dataOut = new byte[1026];
            System.arraycopy(dataSeq, 0, dataOut, 0, 2);
            System.arraycopy(dataRead, 0, dataOut, 2, 1024);
            return dataOut;

		}
		public void run() {
			System.out.println("In PacketSender");
			
			try {				
				//read file into stream
				fstrm = new FileInputStream(file_path);
				
				//total number of bytes in the file
				int filesize = fstrm.available();
						
				System.out.println("Filesize: " + filesize);
	            while(!isTransferOver) {
	            	//check windows size whether full or not, if not send 
	            	if(curSeqNum < baseSeqNum + window_size_N) {
	            		lock.acquire();
	            		if(baseSeqNum == curSeqNum)
	            			timeoutChecker(baseSeqNum);
	            		
	            		byte[] dataOut = new byte[1026];
	            		boolean isLastSeqNum = false;
	            		
	            		// if packet is in packetsList, retrieve from list
						System.out.println("packetsList.size(): " + sentPackets.size());
						if (curSeqNum <= sentPackets.size()){
							System.out.println("nextSeqNum is in vector: " + curSeqNum);
							dataOut = sentPackets.get(curSeqNum-1);
						}
						// else create packet and add to list
						else {
							byte[] dataRead = new byte[packetDataSize];
							int amountRead = fstrm.read(dataRead,0,1024);
							//System.out.print("Sent Data:" + Arrays.toString(dataRead)); 
							//if stream is empty
							if(amountRead == -1) {
									System.out.println("The file read is finished");
									isLastSeqNum = true;
									//dataOut = createPacket(curSeqNum, new byte[0]);
							}
							//if stream is not empty
							else {
								//System.out.print("Sent Data:" + Arrays.toString(dataOut)); 	
								System.out.println("nextSeqNum: " + curSeqNum);
								dataOut = createPacket(curSeqNum, dataRead);
								//System.out.print("Sent Data:" + Arrays.toString(dataOut)); 							
							}
							if(!isLastSeqNum) {
								System.out.println("Sender: Sent seqNum " + curSeqNum);
								sentPackets.add(dataOut);
							}
						}
	            		
						if(!isLastSeqNum) {
							DatagramPacket sendPacket = new DatagramPacket(dataOut, dataOut.length, InetAddress.getByName(receiver_ip_address),receiver_port);
							clientSocket.send(sendPacket);		
						}
						//System.out.println("Sender: Sent seqNum " + curSeqNum);
						if(!isLastSeqNum) {
							curSeqNum++;
						}
						
	            		lock.release();	            		
	            		
	            	}
	            	sleep(10);
	            }	            	           
				fstrm.close();
				endTime = System.currentTimeMillis() - startTime;
				int a = (int)(endTime);
				System.out.println("Total File Transfer Time is " + a + " milliseconds");
				System.out.println("GBNSender is closed");
				System.exit(1);
				
			} catch (SocketException e){
				e.printStackTrace();
			} catch (IOException e){
				e.printStackTrace();
			} catch(InterruptedException e) {
				e.printStackTrace();
			}
		}
	}
	
	public class ACKListener extends Thread {
		
		private DatagramSocket clientSocket;
		private DatagramPacket packetIn;
		private Timeout mytimeout;
		private byte[] dataIn;
		
		public ACKListener(DatagramSocket clientSocket) {
			this.clientSocket = clientSocket;
			this.packetIn = null;
			this.mytimeout = new Timeout();
			this.dataIn = new byte[2];
		}
		
		int getACKNum(byte[] packet) {
			byte[] dataInput = new byte[2];
			System.arraycopy(packet, 0, dataInput, 0, 2);
			int ACKNum = ((dataInput[0] & 0xff) << 8) | (dataInput[1] & 0xff);
			return ACKNum;
		}
		
		public void run() {
			System.out.println("In ACKListener");
			dataIn = new byte[2];
			packetIn = new DatagramPacket(dataIn, dataIn.length);
			
			while(!isTransferOver) {
				try {
					clientSocket.receive(packetIn);
					int ACKNum = getACKNum(dataIn);
					System.out.println("Received Ack " + ACKNum);
					
					//Now check the captured ACKs 
					//if there is a duplicate ACK
					if(baseSeqNum == ACKNum + 1) {
						//update the shared curSeqNum
						lock.acquire();
						curSeqNum = baseSeqNum;
						System.out.println("Duplicate Ack with: " + ACKNum);
						lock.release();
						//update done so unlocked
					}
					//last ACK number, in our case it will be file size in KBs so 10240
					else if(ACKNum == numberOfPackets){
						isTransferOver = true;
					}
					//regular ACK
					else {
						//new base is the ACKNum++ (only listener changes baseSeqNum so no lock while updating)
						if(baseSeqNum <= ACKNum) {
							baseSeqNum = ACKNum + 1;					
							timeoutChecker(baseSeqNum);
						}
					}
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch(InterruptedException e) {
					e.printStackTrace();
				}
				
			}
			//if transfer is completed, close the socket
			clientSocket.close();
		}
	}
	
	public class Timeout extends TimerTask{
		
		public void run() {
			try {
				lock.acquire();
				System.out.println("Timeout occured!");
				curSeqNum = baseSeqNum;
				lock.release();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}
	
	public void timeoutChecker(int seq) {
			timer = new Timer();
			//System.out.println("Timer for: " + "sequence " + seq);
			timer.schedule(new Timeout(), timeout);
	}
	
	public static void main(String[] args) {
		System.out.println("GBNSender Application");
		
		//get the command line arguements
		String str_file_path = args[0];
		String str_receiver_ip_address = args[1]; 
		String str_receiver_port = args[2];
		String str_window_size_N = args[3];
		String str_timeout = args[4];
		
		//put the command line arguements into global variables
		file_path = str_file_path;
		receiver_ip_address = str_receiver_ip_address;
		receiver_port = Integer.parseInt(str_receiver_port);
		window_size_N = Integer.parseInt(str_window_size_N);
		timeout = Integer.parseInt(str_timeout);
		
		startTime = System.currentTimeMillis();
		new GBNSender();			 				
	}
}