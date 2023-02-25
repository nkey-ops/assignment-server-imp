package tcpclient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

public class TCPClient {
	private boolean shutdown;
	private Integer timeout;
	private Integer limit;

	public TCPClient(boolean shutdown, Integer timeout, Integer limit) {
		this.shutdown = shutdown;
		this.timeout = timeout;
		this.limit = limit;
	}

	public byte[] askServer(String host, int port, byte[] toServer) throws IOException {

		try (Socket clientSocket = new Socket(host, port);
				OutputStream output = clientSocket.getOutputStream();
				InputStream in = clientSocket.getInputStream()) {

			output.write(toServer);

			if (shutdown) {
				clientSocket.shutdownOutput();
			}

			// The socket will set time out if timeout was not null.
			// Waiting till the time is elapsed or receive the response
			if (timeout != null) {
				clientSocket.setSoTimeout(timeout);
				long upperBound = System.currentTimeMillis() + timeout;

				while (in.available() < toServer.length && System.currentTimeMillis() <= upperBound)
					Thread.onSpinWait();

			} else { // wait till receive all the data or limited amount

				if (limit != null)
					while (in.available() < toServer.length && in.available() <= limit)
						Thread.onSpinWait();
				else
					while (in.available() < toServer.length)
						Thread.onSpinWait();
			}

			output.flush();

			// if limit is not null read limit bytes else read all available
			return limit != null ? read(in, limit) : in.readNBytes(in.available());
		} catch (IOException e) {
			e.printStackTrace();
		}

		return new byte[0];
	}

	private void closeSocket(Socket socket) {
		try {
			System.out.println("Closing socket: " + socket);
			socket.close();
		} catch (IOException e) {
			System.out.println("Close socket failed: " + socket);
		}
	}

	/**
	 * Read bytes from server
	 *
	 * @param is the input from server
	 * @return the result as String response.
	 * @throws IOException
	 */
	protected byte[] read(InputStream is, int limit) throws IOException {
		if (limit < 0)
			throw new IllegalArgumentException("Limit cannot be below zero");

		return is.readNBytes(is.available() > limit ? limit : is.available());
	}
}
