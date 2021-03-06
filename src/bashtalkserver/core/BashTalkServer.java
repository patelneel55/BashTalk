package bashtalkserver.core;

import java.awt.*;
import java.io.*;
import java.math.*;
import java.net.*;
import java.security.*;
import java.time.*;
import java.time.format.*;
import java.util.*;

import javax.swing.*;

public class BashTalkServer {
	
	private static final int PORT = 9898;
	private static String hashedPassword = "";
	private static ArrayList<Client> clients = new ArrayList<Client>();
	private static ArrayList<String> messageCache = new ArrayList<String>();
	private static final int MAX_CLIENTS = 50;
	private static final int MAX_CACHE_SIZE = 100;
	private static final String HELP_TEXT = "\n\tClear terminal: /clear\n\tExit terminal: /exit\n\tClear Cache (superuser): /clear_cache\n\tUsers online: /users\n\tPrivate Message: /pmsg <user> <message>\n\tMute: /mute\n\tUnmute: /unmute\n\tBan (superuser): /ban <user>";
	
	public static void main(String[] args) throws Exception
	{
		
		final String HOST = getExternalIp();
		int clientNumber = 0;
		boolean useTerminal = false;
		
		// Set terminal flag if -t argument is present
		if (args.length > 0)
			if (args[0].equals("-t"))
				useTerminal = true;
			else
			{
				System.out.println("Valid options: -t");
				System.exit(0);
			}
		
		// Ask user to set admin password
		if (!useTerminal)
			setUIHints();
		
		String temp1, temp2;
		do
		{
			
			if (!useTerminal)
			{
				
				temp1 = JOptionPane.showInputDialog(null, "Enter new administrator password:");
				
				// User hit cancel
				if (temp1 == null)
					System.exit(0);
				
				temp2 = JOptionPane.showInputDialog(null, "Confirm password:");
				
				// User hit cancel
				if (temp2 == null)
					System.exit(0);
				
			}
			else
			{
				
				Scanner in = new Scanner(System.in);
				
				System.out.print("Enter new administrator password: ");
				temp1 = in.nextLine();
				
				System.out.print("Confirm password: ");
				temp2 = in.nextLine();
				
				in.close();
				
			}
			
			if (!temp1.equals(temp2))
				if (!useTerminal)
					JOptionPane.showMessageDialog(null, "Passwords did not match. Please try again.");
				else
					System.out.println("Passwords did not match. Please try again.");
				
		} while (!temp1.equals(temp2));
		
		// Store user password
		hashedPassword = hashString(temp1);
		
		// Notify the user that the server has started
		if (!useTerminal)
			JOptionPane.showMessageDialog(null, "The BashTalk Server has started!");
		else
		{
			// Clear terminal or cmd screen
			clearOutput();
			System.out.println("-- BashTalk Server --");
			System.out.println("Local: " + getLocalIp() + "[" + PORT + "]");
			System.out.println("External: " + HOST + "[" + PORT + "]");
			System.out.println("");
		}
		
		ServerSocket listener = new ServerSocket(PORT);
		try
		{
			while (true)
				if (clients.size() < MAX_CLIENTS)
				{
					clients.add(new Client(clientNumber++, listener.accept()));
					clients.get(clients.size() - 1).start();
				}
				else
				{
					Socket socket = listener.accept();
					PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
					out.println("Maximum number of clients reached.");
					// Client closes self
				}
		}
		finally
		{
			broadcastMsg("shutdown");
			listener.close();
		}
	}
	
	/* Set the UI to the correct font based on the scale and the resolution */
	private static void setUIHints()
	{
		// Calculates scaling unique to each screen resolution
		GraphicsDevice gd = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
		int scale = (gd.getDisplayMode().getHeight() + gd.getDisplayMode().getWidth()) / 200;
		
		UIManager.put("OptionPane.messageFont", new Font("Consolas", Font.PLAIN, (int) (scale * 1.5)));
		UIManager.put("OptionPane.buttonFont", new Font("Consolas", Font.BOLD, (int) (scale * 1.5)));
	}
	
	public static class Client extends Thread {
		
		public int clientNumber;
		public String username;
		private BufferedReader in;
		private PrintWriter out;
		private Socket socket;
		private boolean muted;
		
		public Client(int clientNumber, Socket socket)
		{
			this.clientNumber = clientNumber;
			this.socket = socket;
			this.muted = false;
		}
		
		/* Returns the username of this client */
		public String getUsername()
		{
			return this.username;
		}
		
		/* Returns the mute status of this client */
		public boolean getMuted()
		{
			return this.muted;
		}
		
		/* Sets the mute status of this client */
		public void setMuted(boolean state)
		{
			this.muted = state;
		}
		
		@Override
		public void run()
		{
			try
			{
				// Initialize streams
				this.in = new BufferedReader(new InputStreamReader(this.socket.getInputStream()));
				this.out = new PrintWriter(this.socket.getOutputStream(), true);
				
				// Handle username
				while (true)
				{
					directMsg("Please enter a valid username: ");
					String tempUsername = this.in.readLine();
					
					// Check if username is already online
					boolean valid = true;
					for (Client c : clients)
						if (c.getUsername() != null && c.getUsername().equals(tempUsername))
						{
							valid = false;
							break;
						}
					
					// If the username is valid
					if (valid && tempUsername.length() > 0)
					{
						
						directMsg("Username approved. Welcome.");
						log(tempUsername + " has joined the server as client #" + this.clientNumber + ".");
						this.username = tempUsername;
						
						// Break out of error trap
						break;
						
					}
					else
						directMsg("Username already online. Please try again.");
				}
				
				// Send cached messages
				for (String msg : messageCache)
					directMsg(msg);
				directMsg("-- End of Message History --");
				
				// Notify group of join
				broadcastMsg(this.username + " has joined the server.");
				
				// Send the list of online users
				directMsg(getOnlineUsers());
				
				// Wait for messages from client
				while (true)
				{
					
					// Get the message that is sent to the server
					String msg = this.in.readLine();
					
					// Ignore empty message with no formatting, since reload speed can exceed KeyListener refresh speed
					if (msg.equals(""))
						continue;
					
					String command = extractMessageSegments(msg, 0)[3];
					
					if (!command.equals(""))
					{
						if (command.equals("/exit"))
						{
							// Close the connection for this client
							close(true);
							break;
						}
						else if (command.equals("/clear_cache"))
						{
							// Clear the server's cache of stored messages (requires password)
							// Messages on user's screens are preserved for now
							
							// Request, receieve, hash, and check password against stored hash
							if (promptAndValidatePassword())
							{
								messageCache.clear();
								serverMsg("Cache cleared.");
							}
							else
								serverMsg("Authentication failed.");
						}
						else if (command.equals("/pmsg"))
						{
							// Send a private message to another online user
							// Format: [time] <usr> /cmd <usr1> <msg>
							
							// Extract segments with one expected argument
							String[] segments = extractMessageSegments(msg, 1);
							
							// Check for errors from extraction
							
							if (segments.length == 1)
							{
								if (segments[0].equals("Too few arguments") || segments[0].equals("No command found"))
									directMsg("\nUsage: /pmsg <user> <message>");
								else
									directMsg("\nUnknown error extracting message segments.");
								
								continue;
							}
							
							// Get client by username
							Client c = getClient(segments[4]);
							
							if (c != null)
							{
								// Add "Private: " + timestamp + <sender@arg1> + message
								String pmsg = "Private: " + segments[0] + " <" + segments[1] + "@" + segments[4] + "> " + segments[2];
								
								// Send back to sender and receiver
								c.directMsg(pmsg);
								directMsg(pmsg);
								
							}
							else
								serverMsg("\"" + segments[4] + "\" is not online.");
							
						}
						else if (command.equals("/users"))
							// Return the list of users online
							directMsg(getOnlineUsers());
						else if (command.equals("/help"))
							// Prints all possible commands available
							directMsg(HELP_TEXT);
						else if (this.muted)
							serverMsg("You are currently muted.");
						else if (command.equals("/mute"))
						{
							
							// Mute the specified user
							
							// Extract segments with one expected argument
							String[] segments = extractMessageSegments(msg, 1);
							
							if (segments.length == 1)
							{
								if (segments[0].equals("Too few arguments") || segments[0].equals("No command found"))
									directMsg("\nUsage: /mute <user>");
								else
									directMsg("\nUnknown error extracting message segments.");
								
								continue;
							}
							
							// Get the targeted client
							Client c = getClient(segments[4]);
							
							if (c != null)
							{
								
								// Mute the user
								c.setMuted(true);
								
								// Notify the group
								broadcastMsg(getTimestamp() + " <" + getUsername() + "> muted <" + c.getUsername() + ">.");
								
							}
							else
								serverMsg("\"" + segments[4] + "\" is not online.");
							
						}
						else if (command.equals("/unmute"))
						{
							
							// Unmute the specified user
							
							// Extract segments with one expected argument
							String[] segments = extractMessageSegments(msg, 1);
							
							if (segments.length == 1)
							{
								if (segments[0].equals("Too few arguments") || segments[0].equals("No command found"))
									directMsg("\nUsage: /unmute <user>");
								else
									directMsg("\nUnknown error extracting message segments.");
								
								continue;
							}
							
							// Get the targeted client
							Client c = getClient(segments[4]);
							
							if (c != null)
							{
								
								// Unmute the user
								c.setMuted(false);
								
								// Notify the group
								broadcastMsg(getTimestamp() + " <" + getUsername() + "> unmuted <" + c.getUsername() + ">.");
								
							}
							else
								serverMsg("\"" + segments[4] + "\" is not online.");
							
						}
						else if (command.equals("/ban"))
						{
							
							// Ban the specified user from the server (requires password)
							
							// Extract segments with one expected argument
							String[] segments = extractMessageSegments(msg, 1);
							
							if (segments.length == 1)
							{
								if (segments[0].equals("Too few arguments") || segments[0].equals("No command found"))
									directMsg("\nUsage: /ban <user>");
								else
									directMsg("\nUnknown error extracting message segments.");
								
								continue;
							}
							
							// Get the targeted client
							Client c = getClient(segments[4]);
							
							if (promptAndValidatePassword() && c != null)
							{
								
								// Ban the user
								clients.remove(c);
								c.directMsg("banned"); // Trigger banned routine in client
								
								// Notify the group
								broadcastMsg(getTimestamp() + " <" + c.getUsername() + "> was banned from the server.");
								
							}
							else if (c == null)
								serverMsg("\"" + segments[4] + "\" is not online.");
							else
								serverMsg("Authentication failed.");
							
						}
						else
							// Command not found; Send help text
							directMsg("\tInvalid command: \"" + command + "\"\n" + HELP_TEXT);
					}
					else
					{
						// No special commands found. Broadcast the message (unless the user is muted)
						
						if (!this.muted)
						{
							broadcastMsg(msg);
							messageCache.add(msg);
						}
						else
							serverMsg("You are currently muted.");
						
						// Remove the oldest message if cache is full
						if (messageCache.size() > MAX_CACHE_SIZE)
							messageCache.remove(0);
					}
				}
			}
			catch (IOException e)
			{
				log("Error handling client #" + this.clientNumber + ": " + e);
				
				// If the client is still in the client list, notify the group that it logged off
				if (clients.contains(this))
					close(true);
				else
					close(false);
			}
		}
		
		/* Send a message to only this client. */
		public void directMsg(String msg)
		{
			this.out.println(msg);
		}
		
		/* Send a message with server formatting to only this client. */
		private void serverMsg(String msg)
		{
			directMsg(getTimestamp() + " <# server #> " + msg);
		}
		
		/* Prompt user for password, hash reply, and check against stored hash */
		private boolean promptAndValidatePassword()
		{
			
			// Prompt for user password
			serverMsg("Enter password: ");
			
			// Wait for user reply and extract password
			try
			{
				
				String[] segments = extractMessageSegments(this.in.readLine(), 0);
				String password = segments[2];
				return hashString(password).equals(hashedPassword);
				
			}
			catch (IOException e)
			{
				
				directMsg("Error handling password.");
				log("Error handling password for user: " + getUsername() + ".");
				return false;
				
			}
			
		}
		
		/*
		 * Close the connection to this client. Optionally notify all users that the
		 * client has left the server.
		 */
		private void close(boolean notify)
		{
			try
			{
				clients.remove(this);
				this.in.close();
				this.out.close();
				this.socket.close();
				log(this.username + " has left the server.");
				if (notify)
					broadcastMsg(this.username + " has left the server.");
			}
			catch (Exception e)
			{
				log("Error closing socket #" + this.clientNumber + ": " + e);
			}
		}
		
	}
	
	/* Log a message to the server's screen */
	private static void log(String msg)
	{
		System.out.println(getTimestamp() + " " + msg);
	}
	
	/* Clear the terminal or cmd screen */
	private static void clearOutput()
	{
		if (System.getProperty("os.name").toLowerCase().indexOf("win") != -1)
		{
			// Clear command prompt
			try
			{
				new ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor();
			}
			catch (Exception err)
			{
				err.printStackTrace();
				System.out.println("Error clearing screen. My bad.");
			}
		}
		else
		{
			// Clear terminal
			System.out.println("\033[H\033[2J");
		}
	}
	
	/* Send a message to all clients in the client pool. */
	private static void broadcastMsg(String msg)
	{
		for (Client client : clients)
			try
			{
				client.directMsg(msg);
			}
			catch (Exception e)
			{
				System.out.println("Error sending message \"" + msg + "\": " + e);
			}
	}
	
	/* Return a list of users currently logged in */
	private static String getOnlineUsers()
	{
		String users = "\nOnline Users: [";
		for (Client c : clients)
			users += c.getUsername() + ", ";
		users = users.substring(0, users.length() - 2) + "]";
		return users;
	}
	
	/* Return the client with the specified username */
	private static Client getClient(String username)
	{
		for (Client c : clients)
			if (username.equals(c.getUsername()))
				return c;
			
		// Client was not found
		return null;
	}
	
	/* Hash a string using SHA-256. */
	private static String hashString(String str)
	{
		try
		{
			MessageDigest msgDg = MessageDigest.getInstance("SHA-256");
			msgDg.update(str.getBytes());
			return String.format("%032x", new BigInteger(1, msgDg.digest()));
		}
		catch (Exception e)
		{
			System.out.println("Could not find proper hashing algorithim.");
			return null;
		}
	}
	
	/* Return a timestamp of form [HH:mm]. */
	private static String getTimestamp()
	{
		DateTimeFormatter dtf = DateTimeFormatter.ofPattern("HH:mm");
		LocalDateTime now = LocalDateTime.now();
		return "[" + dtf.format(now) + "]";
	}
	
	/*
	 * Converts the segments of a client message into an array of Strings. The array
	 * is formatted as follows:
	 * [0] - timestamp
	 * [1] - sender
	 * [2] - message
	 * [3] - command (must have '/' as leading character) (optional)
	 * [4] - argument1 (optional)
	 * [5] - argument2 (optional)
	 * ...
	 * [3 + x] - argumentX
	 * 
	 * If too few arguments are provided, returns a String[] of length 1 with
	 * "Too few arguments." in the first position. If no command is found but
	 * numberOfArgs > 0, returns a String[] of length 1 with "No command found." in
	 * the first position.
	 * 
	 */
	private static String[] extractMessageSegments(String msg, int numberOfArgs)
	{
		
		final int MIN_LEN = 3; // Minimum number of positions required for a message
		final int MSG_ARG_START = 3; // Position at which arguments start in msg
		final int SEG_ARG_START = MIN_LEN + 1; // Arguments start at MIN_LEN + 1 in fSegments because an additional
												// position is needed for command
		
		// Create array for final segments
		String[] fSegments = new String[SEG_ARG_START + numberOfArgs];
		
		// Split raw segments at each space
		String[] rSegments = msg.split(" ");
		
		// Check if there are less segments than needed
		// If there are too few, return the error
		// Handicap rSegments by 1 because fSegments always has a command field
		if (rSegments.length + 1 < fSegments.length && numberOfArgs > 0)
			return new String[] {"Too few arguments"};
		
		// Store the basic info
		fSegments[0] = rSegments[0]; // Timestamp
		fSegments[1] = rSegments[1].substring(1, rSegments[1].length() - 1); // Store sender without < and >
		fSegments[2] = ""; // Initialize the message segment so that it can be added to below
		fSegments[3] = rSegments.length > 2 && rSegments[2].indexOf("/") == 0 ? rSegments[2] : ""; // Store command if it begins with a /
		
		// Check if arguments were requested (implies a command), but no command was
		// found
		if (numberOfArgs > 0 && fSegments[3].length() == 0)
			return new String[] {"No command found"};
			
		// Collect arguments
		// Args fill [SEG_ARG_START] through [SEG_ARG_START + numberOfArgs - 1] in
		// fSegments
		// and [MSG_ARG_START] through [MSG_ARG_START + numberOfArgs - 1] in rSegments
		for (int i = 0; i < numberOfArgs; i++)
			fSegments[SEG_ARG_START + i] = rSegments[MSG_ARG_START + i];
			
		// Collect message segments and re-join with a space.
		// Message segments starts at MIN_LEN + fSegments[3].indexOf("/") +
		// numberOfArgs.
		// fSegments[3].indexOf("/") checks to see if a valid command was found.
		// If command is not found, indexOf will return -1.
		// This will shift msgStart back one position to include what would usually be a
		// command as part of the message.
		// If a command is found, indexOf will return 0, leaving the msgStart unaffected
		// and the command out of the message
		int msgStart = MIN_LEN + fSegments[3].indexOf("/") + numberOfArgs;
		for (int i = msgStart; i < rSegments.length; i++)
		{
			
			// Add a space for every word after the first
			if (i != msgStart)
				fSegments[2] += " ";
			
			// Add each word of the message
			fSegments[2] += rSegments[i];
			
		}
		
		return fSegments;
	}
	
	/* Get the local IP of the host device */
	public static String getLocalIp()
	{
		try
		{
			InetAddress ipAddr = InetAddress.getLocalHost();
			return ipAddr.getHostAddress();
		}
		catch (Exception e)
		{
			e.printStackTrace();
			System.exit(1);
			
			return null; // To make Eclipse shutup
		}
	}
	
	/* Get the external IP of the host device. */
	public static String getExternalIp() throws Exception
	{
		URL AWSCheck = new URL("http://checkip.amazonaws.com");
		BufferedReader in = null;
		try
		{
			in = new BufferedReader(new InputStreamReader(AWSCheck.openStream()));
			return in.readLine();
		}
		finally
		{
			if (in != null)
				try
				{
					in.close();
				}
				catch (IOException e)
				{
					e.printStackTrace();
				}
		}
	}
}
