import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ConcHTTPAsk {
	private boolean isStopped = false;
	private final int port;
	private final String host = "localhost";

	public ConcHTTPAsk(int port) {
		this.port = port;
	}

	public static void main(String[] args) {
		if (args.length != 1 && args[0].matches("\\d+")) {
			log("Usage: java HTTPAsk <port 11>");
			System.exit(1);
		}

		int port = Integer.parseInt(args[0]);
		ConcHTTPAsk ask = new ConcHTTPAsk(port);
		ask.open();

	}

	public boolean isStopped() {
		return isStopped;
	}

	public void terminate() {
		this.isStopped = true;
	}

	public int getPort() {
		return port;
	}

	public String getHost() {
		return host;
	}

	private void open() {
		try (ServerSocket serverSocket = new ServerSocket(port)) {
			log("Started connection at port: " + port);

			do {
				Socket clientSocket = serverSocket.accept();
				
				RequestHandler handler = new RequestHandler(clientSocket, this);
				new Thread(handler).start();
			} while (!isStopped);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		log("Server was stopped");
	}

	private static void log(String s) {
		System.out.println(s);
	}

	private static class RequestHandler implements Runnable {
		private final Socket socket;
		private final ConcHTTPAsk concHTTPAsk;

		public RequestHandler(Socket socket, ConcHTTPAsk concHTTPAsk) {
			this.socket = socket;
			this.concHTTPAsk = concHTTPAsk;
		}

		@Override
		public void run() {
			log("New Session was started");
			
			HTTPAsk httpAsk = new HTTPAsk(concHTTPAsk);
			httpAsk.handle(socket);
			
			log("Session was terminated");
		}

	}

	private static class HTTPAsk {
		private final ConcHTTPAsk server;

		public HTTPAsk(ConcHTTPAsk concHTTPAsk) {
			this.server = concHTTPAsk;
		}

		public void handle(Socket socket) {
			try (socket) {
				try (OutputStream out = socket.getOutputStream();
						InputStream in = socket.getInputStream()) {

					Optional<HttpRequest> oHttpRequest = parseRequest(in);

					if (oHttpRequest.isEmpty()) {
						writeResponse(new HTTPResponse(400), out);
						return;
					}

					HttpRequest httpRequest = oHttpRequest.get();
					String method = httpRequest.method();
					String path = httpRequest.uri().getPath();
					boolean isHTTP = httpRequest.uri().getScheme().equalsIgnoreCase("http");

					HTTPResponse response;

					if (method.equals("GET") && isHTTP && path.matches("/ask"))
						response = ask(httpRequest);

					else if (method.equals("GET") && path.matches("/stop")) {
						response = new HTTPResponse(200);
						server.terminate();
					} else
						response = new HTTPResponse(404);

					writeResponse(response, out);
				} 
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}

		private static class Options {
			public final String host;
			public final int port;
			public final boolean shutdown;
			public final Integer timeout;
			public final Integer limit;
			public final byte[] data;

			public Options(String host, int port, boolean shutdown, Integer timeout, Integer limit, byte[] data) {
				Objects.requireNonNull(host);
				Objects.requireNonNull(data);

				this.host = host;
				this.port = port;
				this.shutdown = shutdown;
				this.timeout = timeout;
				this.limit = limit;
				this.data = data;
			}

		}

		private static class HTTPResponse {
			public final int statusCode;
			public final String body;

			public HTTPResponse(int statusCode, String body) {
				this.statusCode = statusCode;
				this.body = body;
			}

			public HTTPResponse(int statusCode) {
				this.statusCode = statusCode;
				this.body = "";
			}
		}

		private HTTPResponse ask(HttpRequest httpRequest) {
			Map<String, String> params = getParams(httpRequest.uri());
			Optional<Options> optionalOptions = getOptions(params);

			if (optionalOptions.isEmpty())
				return new HTTPResponse(400);

			Options options = optionalOptions.get();
			TCPClient client = new TCPClient(options.shutdown, options.timeout, options.limit);

			try {
				byte[] serverResponse = client.askServer(options.host, options.port, options.data);

				return new HTTPResponse(200, new String(serverResponse));
			} catch (IOException e) {
				return new HTTPResponse(400);
			}
		}

		private static Optional<Options> getOptions(Map<String, String> params) {
			if (!params.containsKey("hostname") 
					|| !params.containsKey("port") 
					|| !params.get("port").matches("\\d+"))
				return Optional.empty();

			String host = params.get("hostname");
			int port = Integer.parseInt(params.get("port"));

			boolean shutdown = false;
			Integer timeout = null;
			Integer limit = null;
			byte[] toServer = new byte[0];

			if (params.containsKey("shutdown")) {
				String sShutdown = params.get("shutdown");

				if ("true".equalsIgnoreCase(sShutdown) || "false".equalsIgnoreCase(sShutdown))
					shutdown = Boolean.parseBoolean(sShutdown);
				else
					return Optional.empty();
			}

			if (params.containsKey("limit"))
				if (params.get("limit").matches("\\d+"))
					limit = Integer.parseInt(params.get("limit"));
				else
					return Optional.empty();

			if (params.containsKey("timeout"))
				if (params.get("timeout").matches("\\d+"))
					timeout = Integer.parseInt(params.get("timeout"));
				else
					return Optional.empty();

			if (params.containsKey("string"))
				toServer = params.get("string").getBytes();

			Options options = new Options(host, port, shutdown, timeout, limit, toServer);
			return Optional.of(options);
		}

		private void writeResponse(HTTPResponse httResponse, OutputStream out) throws IOException {
			ByteArrayOutputStream writer = new ByteArrayOutputStream();

			String response = "HTTP/1.0 " + httResponse.statusCode + "\r\n"
					+ "Content-Type: text/html; charset=utf-8\r\n" + "\r\n" + httResponse.body;

			writer.write(response.getBytes());
			writer.flush();
			writer.writeTo(out);
		}

		private Map<String, String> getParams(URI uri) {
			Map<String, String> queryPairs = new LinkedHashMap<String, String>();

			String query = uri.getQuery();
			if (query.isBlank())
				return queryPairs;

			String[] pairs = query.split("&");

			try {
				for (String pair : pairs) {
					int idx = pair.indexOf("=");

					queryPairs.put(URLDecoder.decode(pair.substring(0, idx), "UTF-8"),
							URLDecoder.decode(pair.substring(idx + 1), "UTF-8"));
				}
			} catch (UnsupportedEncodingException e) {
				throw new RuntimeException(e);
			}

			return queryPairs;

		}

		private Optional<HttpRequest> parseRequest(InputStream in) {
			Optional<String> requestLine = getRequestLine(in, Charset.forName("UTF-8"));

			if (requestLine.isEmpty())
				return Optional.empty();

			String[] requestComponents = requestLine.get().split("\\s");

			if (requestComponents.length != 3)
				return Optional.empty();

			String method = requestComponents[0];
			String uri = requestComponents[1];
			String protocol = requestComponents[2].replaceFirst("[/].*", "");

			URL url;
			try {
				url = new URL(protocol, server.getHost(), server.getPort(), uri);

				return Optional.of(HttpRequest.newBuilder(url.toURI()).method(method, BodyPublishers.noBody()).build());

			} catch (MalformedURLException | URISyntaxException e) {
				return Optional.empty();
			}
		}

		private Optional<String> getRequestLine(InputStream in, Charset charset) {
			Objects.requireNonNull(in);
			try {
				int i;

				String requestLine = "";

				while ((i = in.read()) != -1 && i != '\r')
					requestLine += String.valueOf((char) i);

				return Optional.of(URLDecoder.decode(requestLine, charset));
			} catch (Exception e) {
				return Optional.empty();
			}
		}
	}

	private static class TCPClient {
		private boolean shutdown;
		private Integer timeout;
		private Integer limit;

		public TCPClient(boolean shutdown, Integer timeout, Integer limit) {
			this.shutdown = shutdown;
			this.timeout = timeout;
			this.limit = limit;
		}

		public byte[] askServer(String host, int port, byte[] toServer) throws UnknownHostException, IOException {

			try (Socket clientSocket = new Socket(host, port);
					OutputStream output = clientSocket.getOutputStream();
					InputStream in = clientSocket.getInputStream()) {

				System.out.println("Connected to:" + host + ":" + port);

				output.write(toServer);

				if (shutdown) {
					clientSocket.shutdownOutput();
				}

				// The socket will set time out if timeout was not null.
				// Waiting till the time is elapsed or receive the response
				if (timeout != null) {
					clientSocket.setSoTimeout(timeout);
					long upperBound = System.currentTimeMillis() + timeout;

					while (toServer.length == 0 ? in.available() == 0
							: in.available() < toServer.length && System.currentTimeMillis() <= upperBound)
						Thread.onSpinWait();

				} else { // wait till receive all the data or limited amount

					if (limit != null)
						while (in.available() < toServer.length && in.available() <= limit)
							Thread.onSpinWait();
					else
						while (toServer.length == 0 ? in.available() == 0 : in.available() < toServer.length)
							Thread.onSpinWait();
				}

				output.flush();

				// if limit is not null read limit bytes else read all available
				return limit != null ? read(in, limit) : in.readNBytes(in.available());
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
}
